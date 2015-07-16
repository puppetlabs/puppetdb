(ns com.puppetlabs.puppetdb.test.cli.import-export-roundtrip
  (:require [com.puppetlabs.puppetdb.cli.export :as export]
            [com.puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.trapperkeeper.core :as tk]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.testutils :as testutils]
            [fs.core :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [clj-http.client :as client]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.command.constants :refer [command-names]]
            [com.puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [com.puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]]
            [com.puppetlabs.puppetdb.testutils.reports :as tur]
            [clojure.walk :as walk]
            [clj-time.core :refer [now]]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.puppetdb.utils :as utils]
            [clojure.tools.logging.impl :as li]
            [slingshot.slingshot :refer [throw+]]
            [com.puppetlabs.puppetdb.testutils.jetty :as jutils]))

(use-fixtures :each fixt/with-test-logging-silenced)

(defn block-until-queue-empty
  "Blocks the current thread until all messages from the queue have been processed."
  []
  (loop [depth (jutils/current-queue-depth)]
    (when (< 0 depth)
      (Thread/sleep 10)
      (recur (jutils/current-queue-depth)))))

(defn submit-command
  "Submits a command to the running PuppetDB, launched by `puppetdb-instance`."
  [cmd-kwd version payload]
  (command/submit-command-via-http! "localhost" jutils/*port* (command-names cmd-kwd) version payload))

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

(defn block-on-node
  "Waits for the queue to be empty, then blocks until the catalog, facts and reports are all
   found for `node-name`. Ensures that the commands have been stored before proceeding in a test."
  [node-name]
  (block-until-queue-empty)
  (let [catalog-fut (block-until-results 100 (json/parse-string (export/catalog-for-node "localhost" jutils/*port* node-name)))
        report-fut (block-until-results 100 (export/reports-for-node "localhost" jutils/*port* node-name))
        facts-fut (block-until-results 100 (export/facts-for-node "localhost" jutils/*port* node-name))]
    @catalog-fut
    @report-fut
    @facts-fut))

(deftest test-basic-roundtrip
  (let [facts {:name "foo.local"
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"
                        :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}}
        export-out-file (testutils/temp-file "export-test" ".tar.gz")
        catalog (-> (get-in wire-catalogs [5 :empty])
                    (assoc :name "foo.local"))
        report (:basic reports)]

    (jutils/with-puppetdb-instance
      (is (empty? (export/get-nodes "localhost" jutils/*port*)))
      (submit-command :replace-catalog 5 catalog)
      (submit-command :store-report 3 (tur/munge-example-report-for-storage report))
      (submit-command :replace-facts 3 facts)

      (block-on-node (:name facts))

      (is (= (tuc/munge-catalog-for-comparison :v5 (dissoc catalog :producer-timestamp))
             (tuc/munge-catalog-for-comparison :v5 (json/parse-string (export/catalog-for-node "localhost" jutils/*port* (:name catalog))))))

      (is (= (tur/munge-report-for-comparison (tur/munge-example-report-for-storage report))
             (tur/munge-report-for-comparison (-> (export/reports-for-node "localhost" jutils/*port* (:certname report))
                                                  first
                                                  tur/munge-example-report-for-storage))))
      (is (= facts (export/facts-for-node "localhost" jutils/*port* "foo.local")))

      (export/-main "--outfile" export-out-file "--host" "localhost" "--port" jutils/*port*))

    (jutils/with-puppetdb-instance

      (is (empty? (export/get-nodes "localhost" jutils/*port*)))
      (import/-main "--infile" export-out-file "--host" "localhost" "--port" jutils/*port*)

      (block-on-node (:name facts))

      (is (= (tuc/munge-catalog-for-comparison :v5 (dissoc catalog :producer-timestamp))
             (tuc/munge-catalog-for-comparison :v5 (json/parse-string (export/catalog-for-node "localhost" jutils/*port* (:name catalog))))))
      (is (= (tur/munge-report-for-comparison (tur/munge-example-report-for-storage report))
             (tur/munge-report-for-comparison (-> (export/reports-for-node "localhost" jutils/*port* (:certname report))
                                                  first
                                                  tur/munge-example-report-for-storage))))
      (is (= facts (export/facts-for-node "localhost" jutils/*port* "foo.local"))))))

(defn spit-v3-export-tar
  "Takes mtadata, catalog, facts, report for a node and spits a tarball (with v3 metadata)
   to `tar-path`."
  [tar-path metadata node-catalog node-facts node-report]
  (with-open [tar-writer (archive/tarball-writer tar-path)]
    (utils/add-tar-entry tar-writer {:msg (str "Exporting PuppetDB metadata")
                                     :file-suffix [export/export-metadata-file-name]
                                     :contents (json/generate-pretty-string metadata)})

    (utils/add-tar-entry tar-writer (export/facts->tar (:name node-facts) node-facts))
    (utils/add-tar-entry tar-writer (export/catalog->tar (get-in node-catalog [:data :name])
                                                         (json/generate-string node-catalog)))
    (utils/add-tar-entry tar-writer (first (export/report->tar (:certname node-report)
                                                               [(tur/munge-example-report-for-storage (dissoc node-report :environment))])))))

(deftest test-v3->v4-migration
  (let [facts {:name "foo.local"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"}}
        export-out-file (testutils/temp-file "export-test" ".tar.gz")
        catalog (assoc-in (get-in wire-catalogs [2 :empty])
                          [:data :name] "foo.local")
        report (:basic reports)]

    (spit-v3-export-tar export-out-file
                        {:timestamp (now)
                         :command-versions
                         {:replace-catalog 3
                          :store-report 2
                          :replace-facts 1}}
                        catalog
                        facts
                        report)

    (jutils/with-puppetdb-instance

      (import/-main "--infile" export-out-file "--host" "localhost" "--port" jutils/*port*)

      (block-on-node (:name facts))
      (Thread/sleep 5000)

      (is (= (tuc/munge-catalog-for-comparison :v3 catalog)
             (tuc/munge-catalog-for-comparison :v3 (->> (get-in catalog [:data :name])
                                                        (export/catalog-for-node "localhost" jutils/*port* :v3)
                                                        json/parse-string))))
      (is (= (tur/munge-report-for-comparison (-> report
                                                  (dissoc :environment :status)
                                                  tur/munge-example-report-for-storage))
             (tur/munge-report-for-comparison (-> (first (export/reports-for-node "localhost" jutils/*port* :v3 (:certname report)))
                                                  (update-in [:resource-events] vec)))))

      (is (= facts
             (dissoc
              (export/facts-for-node "localhost" jutils/*port* :v4 "foo.local")
              :environment))))))

(deftest test-max-frame-size
  (let [catalog (-> (get-in wire-catalogs [4 :empty])
                    (assoc :name "foo.local"))]
    (jutils/puppetdb-instance
     (assoc-in (jutils/create-config) [:command-processing :max-frame-size] "1024")
     (fn []
       (is (empty? (export/get-nodes "localhost" jutils/*port*)))
       (submit-command :replace-catalog 5 catalog)
       (is (thrown-with-msg? java.util.concurrent.ExecutionException #"Results not found"
                             @(block-until-results 5 (json/parse-string (export/catalog-for-node "localhost" jutils/*port* "foo.local")))))))))
