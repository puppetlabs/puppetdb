(ns puppetlabs.puppetdb.cli.import-export-roundtrip-test
  (:require [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.cli.anonymize :as anonymize]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.http.command :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.admin :as admin]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(use-fixtures :each fixt/with-test-logging-silenced)

(defn munge-report
  "Munges a catalog of list of reports for comparision.
  Returns a list of reports."
  [report-or-reports]
  (->> report-or-reports
       utils/vector-maybe
       (map (comp tur/munge-report-for-comparison
                  tur/munge-example-report-for-storage))))

(defn munge-catalog
  "Munges a catalog or list of catalogs for comparison.
  Returns a list of catalogs."
  [catalog-or-catalogs]
  (->> catalog-or-catalogs
       utils/vector-maybe
       (map (comp (partial tuc/munge-catalog-for-comparison :v6)
                  #(dissoc % :hash)))))

(defn block-until-queue-empty
  "Blocks the current thread until all messages from the queue have been processed."
  []
  (loop [depth (svc-utils/current-queue-depth)]
    (when (< 0 depth)
      (Thread/sleep 10)
      (recur (svc-utils/current-queue-depth)))))

(defn block-until-results-fn
  "Executes `f`, if results are found, return them, otherwise
  wait and try again. Will throw an exception if results aren't found
  after 100 tries"
  [n f]
  (loop [count 0
         results (f)]
    (cond
     (seq results)
     results

     (< n count)
     (throw+ (format "Results not found after %d iterations, giving up" n))

     :else
     (do
       (Thread/sleep 100)
       (recur (inc count) (f))))))

(defmacro block-until-results
  "Body is some expression that will be executed in a future. All
  errors from the body of the macro are ignored. Will block until
  results are returned from the body of the macro"
  [n & body]
  `(future
     (block-until-results-fn
      ~n
      (fn []
        (try
          (do ~@body)
          (catch Exception e#
            ;; Ignore
            ))))))

(defn parse-tar-entry-contents
  "Parses the first of a list of tar-entries :contents"
  [tar-entries]
  (-> tar-entries
      first
      :contents
      (json/parse-string true)))

(defn command-base-url
  [base-url]
  (assoc base-url
         :prefix "/pdb/cmd"
         :version :v1))

(deftest test-basic-roundtrip
  (let [certname "foo.local"
        facts {:certname certname
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"
                        :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
               :producer_timestamp (to-string (now))}
        export-out-dir (testutils/temp-dir "export-test")
        export-out-file-path (str (.getPath export-out-dir) "/outfile.tar.gz")
        catalog (-> (get-in wire-catalogs [6 :empty])
                    (assoc :certname certname
                           :producer_timestamp (now)))
        report (-> (:basic reports)
                   (assoc :certname certname))]

    (svc-utils/puppetdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))

         (svc-utils/sync-command-post (command-base-url svc-utils/*base-url*) "replace catalog" 6 catalog)
         (svc-utils/sync-command-post (command-base-url svc-utils/*base-url*) "store report" 5
                                      (tur/munge-example-report-for-storage report))
         (svc-utils/sync-command-post (command-base-url svc-utils/*base-url*) "replace facts" 4 facts)

         (is (testutils/=-after? munge-catalog catalog (->> certname
                                                            (export/catalogs-for-node query-fn)
                                                            parse-tar-entry-contents)))

         (is (testutils/=-after? munge-report report (->> certname
                                                          (export/reports-for-node query-fn)
                                                          parse-tar-entry-contents)))
         (is (= facts (->> certname
                           (export/facts-for-node query-fn)
                           parse-tar-entry-contents)))

         (#'export/-main "--outfile" export-out-file-path
                         "--host" (:host svc-utils/*base-url*)
                         "--port" (str (:port svc-utils/*base-url*))))))

    (svc-utils/puppetdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))

         (svc-utils/until-consumed
          3
          (fn []
            (#'import/-main "--infile" export-out-file-path
                            "--host" (:host svc-utils/*base-url*)
                            "--port" (str (:port svc-utils/*base-url*)))))

         ;; For some reason, although the fact's/report's message has
         ;; been consumed and committed, it's not immediately available
         ;; for querying. Maybe this is a race condition in our tests?
         ;; The next two lines ensure that the message is not only
         ;; consumed but present in the DB before proceeding
         @(block-until-results 100 (export/catalogs-for-node query-fn certname))
         @(block-until-results 100 (export/facts-for-node query-fn certname))
         @(block-until-results 100 (export/reports-for-node query-fn certname))

         (is (testutils/=-after? munge-catalog catalog (->> certname
                                                            (export/catalogs-for-node query-fn)
                                                            parse-tar-entry-contents)))
         (is (= facts (->> certname
                           (export/facts-for-node query-fn)
                           parse-tar-entry-contents)))

         (is (testutils/=-after? munge-report report (->> certname
                                                          (export/reports-for-node query-fn)
                                                          parse-tar-entry-contents))))))))

(deftest test-max-frame-size
  (let [certname "foo.local"
        catalog (-> (get-in wire-catalogs [6 :empty])
                    (assoc :certname certname))]
    (svc-utils/puppetdb-instance
     (assoc-in (svc-utils/create-config) [:command-processing :max-frame-size] "1024")
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))
         (pdb-client/submit-command-via-http! (command-base-url svc-utils/*base-url*) "replace catalog" 6 catalog)
         (is (thrown-with-msg?
              java.util.concurrent.ExecutionException #"Results not found"
              @(block-until-results 5 (first
                                       (export/catalogs-for-node query-fn certname))))))))))
