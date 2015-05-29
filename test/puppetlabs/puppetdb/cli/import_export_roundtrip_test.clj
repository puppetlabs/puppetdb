(ns puppetlabs.puppetdb.cli.import-export-roundtrip-test
  (:require [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.client :as pdb-client]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]
            [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [*base-url*]]))

(use-fixtures :each fixt/with-test-logging-silenced)

(defn munge-report
  [report]
  (map (comp tur/munge-report-for-comparison tur/munge-example-report-for-storage)
       (-> report
           utils/vector-maybe)))

(defn munge-catalog
  [catalog]
  (map (partial tuc/munge-catalog-for-comparison :v6)
       (-> catalog
           (dissoc :hash)
           utils/vector-maybe)))

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

(deftest test-basic-roundtrip
  (let [facts {:certname "foo.local"
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"
                        :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
               :producer_timestamp (to-string (now))}
        export-out-file (testutils/temp-file "export-test" ".tar.gz")
        catalog (-> (get-in wire-catalogs [6 :empty])
                    (assoc :certname "foo.local"
                           :producer_timestamp (now)))
        report (:basic reports)]

    (svc-utils/with-puppetdb-instance
      (is (empty? (export/get-nodes *base-url*)))
      
      (svc-utils/sync-command-post (assoc *base-url* :prefix "/pdb/cmd" :version :v1) "replace catalog" 6 catalog)
      (svc-utils/sync-command-post (assoc *base-url* :prefix "/pdb/cmd" :version :v1) "store report" 5
                                   (tur/munge-example-report-for-storage report))
      (svc-utils/sync-command-post (assoc *base-url* :prefix "/pdb/cmd" :version :v1) "replace facts" 4 facts)

      (is (testutils/=-after? munge-catalog catalog (->> (:certname catalog)
                                                         (export/catalogs-for-node *base-url*)
                                                         first)))

      (is (testutils/=-after? munge-report report (->> (:certname report)
                                                       (export/reports-for-node *base-url*)
                                                       first)))
      (is (= facts (->> (:certname facts)
                        (export/facts-for-node *base-url*)
                        first)))

      (#'export/main "--outfile" export-out-file
                     "--host" (:host *base-url*)
                     "--port" (:port *base-url*)))

    (svc-utils/with-puppetdb-instance
      (is (empty? (export/get-nodes *base-url*)))

      (svc-utils/until-consumed
       3
       (fn []
         (#'import/main "--infile" export-out-file
                        "--host" (:host *base-url*) "--port" (:port *base-url*))))

      (is (testutils/=-after? munge-catalog catalog (->> (:certname catalog)
                                                         (export/catalogs-for-node *base-url*)
                                                         first)))

      ;; For some reason, although the fact's/report's message has
      ;; been consumed and committed, it's not immediately available
      ;; for querying. Maybe this is a race condition in our tests?
      ;; The next two lines ensure that the message is not only
      ;; consumed but present in the DB before proceeding
      @(block-until-results 100 (->> (:certname facts)
                                     (export/facts-for-node *base-url*)))
      @(block-until-results 100 (->> (:certname report)
                                     (export/reports-for-node *base-url*)))

      (is (= facts (->> (:certname facts)
                        (export/facts-for-node *base-url*)
                        first)))
      (is (testutils/=-after? munge-report report (->> (:certname report)
                                                       (export/reports-for-node *base-url*)
                                                       first))))))

(deftest test-max-frame-size
  (let [catalog (-> (get-in wire-catalogs [6 :empty])
                    (assoc :certname "foo.local"))]
    (svc-utils/puppetdb-instance
     (assoc-in (svc-utils/create-config) [:command-processing :max-frame-size] "1024")
     (fn []
       (is (empty? (export/get-nodes *base-url*)))
       (pdb-client/submit-command-via-http! (assoc *base-url* :prefix "/pdb/cmd" :version :v1) "replace catalog" 6 catalog)
       (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"Results not found"
            @(block-until-results 5
                                  (->> (:certname catalog)
                                       (export/catalogs-for-node *base-url*)
                                       first))))))))

(defn- check-invalid-url-handling [cmd expected-msg-re]
  (let [ex (is (thrown+-with-msg? #(and (map? %) (:utils/exit-status %))
                                  expected-msg-re
                                  (cmd)))]
    (is (not (zero? (:utils/exit-status ex))))))

(deftest invalid-export-source-handling
  (check-invalid-url-handling
   #(#'export/main "--host" "local:host" "--outfile" "/dev/null" "--port" 10000)
   #"^Invalid source .*"))

(deftest invalid-import-destination-handling
  (check-invalid-url-handling
   #(#'import/main "--host" "local:host" "--infile" "/dev/null" "--port" 10000)
   #"^Invalid destination .*"))
