(ns puppetlabs.puppetdb.cli.import-export-roundtrip-test
  (:require [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as testutils]
            [fs.core :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [clojure.walk :as walk]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.utils :as utils]
            [clojure.tools.logging.impl :as li]
            [puppetlabs.puppetdb.client :as pdb-client]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]
            [puppetlabs.puppetdb.testutils.jetty :as jutils :refer [*base-url*]]))

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
  [base-url cmd-kwd version payload]
  (pdb-client/submit-command-via-http! base-url
                                       (command-names cmd-kwd) version payload))

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
  [base-url node-name]
  (block-until-queue-empty)
  (let [catalog-fut (block-until-results 100 (export/catalog-for-node base-url node-name))
        report-fut (block-until-results 100 (export/reports-for-node base-url node-name))
        facts-fut (block-until-results 100 (export/facts-for-node base-url node-name))]
    @catalog-fut
    @report-fut
    @facts-fut))

(defn- test-basic-roundtrip
  [url-prefix]
  (let [facts {:name "foo.local"
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"
                        :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
               :producer-timestamp (to-string (now))}
        export-out-file (testutils/temp-file "export-test" ".tar.gz")
        catalog (-> (get-in wire-catalogs [5 :empty])
                    (assoc :name "foo.local"))
        report (:basic reports)
        with-server #(jutils/puppetdb-instance
                      (if (empty? url-prefix)
                        (jutils/create-config)
                        (assoc-in (jutils/create-config)
                                  [:global :url-prefix] url-prefix))
                      %)]

    (with-server
      (fn []
        (is (empty? (export/get-nodes *base-url*)))
        (submit-command *base-url* :replace-catalog 5 catalog)
        (submit-command *base-url* :store-report 3
                        (tur/munge-example-report-for-storage report))
        (submit-command *base-url* :replace-facts 3 facts)

        (block-on-node *base-url* (:name facts))

        (is (= (map (partial tuc/munge-catalog-for-comparison :v5)
                    (-> catalog
                      (dissoc :hash)
                      utils/vector-maybe))
               (map (partial tuc/munge-catalog-for-comparison :v5)
                    (-> (export/catalog-for-node *base-url* (:name catalog))
                      (json/parse-string true)
                      (dissoc :hash)
                      utils/vector-maybe))))

        (is (= (tur/munge-report-for-comparison
                (tur/munge-example-report-for-storage report))
               (tur/munge-report-for-comparison
                (-> (export/reports-for-node *base-url* (:certname report))
                  first
                  tur/munge-example-report-for-storage))))
        (is (= facts (export/facts-for-node *base-url* "foo.local")))

        (apply #'export/main
               "--outfile" export-out-file
               "--host" (:host *base-url*) "--port" (:port *base-url*)
               (when-not (empty? url-prefix) ["--url-prefix" url-prefix]))) )

    (with-server
      (fn []
        (is (empty? (export/get-nodes *base-url*)))
        (apply #'import/main
               "--infile" export-out-file
               "--host" (:host *base-url*) "--port" (:port *base-url*)
               (when-not (empty? url-prefix) ["--url-prefix" url-prefix]))

        (block-on-node *base-url* (:name facts))

        (is (= (map (partial tuc/munge-catalog-for-comparison :v5)
                    (-> catalog
                      (dissoc :hash)
                      utils/vector-maybe))
               (map (partial tuc/munge-catalog-for-comparison :v5)
                    (-> (export/catalog-for-node *base-url* (:name catalog))
                      (json/parse-string true)
                      (dissoc :hash)
                      utils/vector-maybe))))
        (is (= (tur/munge-report-for-comparison
                (tur/munge-example-report-for-storage report))
               (tur/munge-report-for-comparison
                (-> (export/reports-for-node *base-url* (:certname report))
                  first
                  tur/munge-example-report-for-storage))))
        (is (= facts (export/facts-for-node *base-url* "foo.local")))))))

(deftest basic-roundtrip
  (test-basic-roundtrip nil))

(deftest url-prefixed-roundtrip
  (test-basic-roundtrip "foo"))

(deftest test-max-frame-size
  (let [catalog (-> (get-in wire-catalogs [5 :empty])
                    (assoc :name "foo.local"))]
    (jutils/puppetdb-instance
     (assoc-in (jutils/create-config) [:command-processing :max-frame-size] "1024")
     (fn []
       (is (empty? (export/get-nodes *base-url*)))
       (submit-command *base-url* :replace-catalog 5 catalog)
       (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"Results not found"
            @(block-until-results 5
                                  (json/parse-string
                                   (export/catalog-for-node *base-url*
                                                            "foo.local")))))))))

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
