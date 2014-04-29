(ns com.puppetlabs.puppetdb.test.cli.import-export-roundtrip
  (:require [com.puppetlabs.puppetdb.cli.export :as export]
            [com.puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [com.puppetlabs.puppetdb.cli.services :refer [puppetdb-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.testutils :as testutils]
            [fs.core :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.trapperkeeper.app :as tka]
            [clj-http.client :as client]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.command.constants :refer [command-names]]
            [com.puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [com.puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]]
            [com.puppetlabs.puppetdb.testutils.reports :as tur]
            [clojure.walk :as walk]
            [clj-time.core :refer [now]]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.puppetdb.utils :as utils]
            [slingshot.slingshot :refer [throw+]]))

(def ^:dynamic *port* nil)

(defn current-port
  "Given a trapperkeeper server, return the port of the running jetty instance.
   Note there can be more than one port (i.e. SSL + non-SSL connector). This only
   returns the first one."
  [server]
  (-> @(tka/app-context server)
      (get-in [:WebserverService :jetty9-server :server])
      .getConnectors
      first
      .getLocalPort))

(defn create-config
  "Creates a default config, populated with a temporary vardir and
   a fresh hypersql instance"
  []
  {:repl {},
   :global {:vardir (testutils/temp-dir)},
   :jetty {:port 0},
   :database (fixt/create-db-map) ,
   :command-processing {}})

(defn current-url
  "Uses the dynamically bound port to create a v4 URL to the
   currently running PuppetDB instance"
  []
  (format "http://localhost:%s/v4/" *port*))

(defn puppetdb-instance
  "Stands up a puppetdb instance with `config`, tears down once `f` returns.
   If the port is assigned by Jetty, use *port* to get the currently running port."
  ([f] (puppetdb-instance (create-config) f))
  ([config f]
     (tkbs/with-app-with-config server
       [jetty9-service puppetdb-service]
       config
       (binding [*port* (current-port server)]
         (f)))))

(defn current-queue-depth
  "Return the queue depth currently running PuppetDB instance (see `puppetdb-instance` for launching PuppetDB)"
  []
  (-> (format "%smetrics/mbean/org.apache.activemq:BrokerName=localhost,Type=Queue,Destination=com.puppetlabs.puppetdb.commands" (current-url))
      (client/get {:as :json})
      (get-in [:body :QueueSize])))

(defn block-until-queue-empty
  "Blocks the current thread until all messages from the queue have been processed."
  []
  (loop [depth (current-queue-depth)]
    (when (< 0 depth)
      (Thread/sleep 10)
      (recur (current-queue-depth)))))

(defn submit-command
  "Submits a command to the running PuppetDB, launched by `puppetdb-instance`."
  [cmd-kwd version payload]
  (command/submit-command-via-http! "localhost" *port* (command-names cmd-kwd) version payload))

(defn block-until-results-fn
  "Executes `f`, if results are found, return them, otherwise
   wait and try again. Will throw an exception if results aren't found
   after 100 tries"
  [f]
  (loop [count 0
         results (f)]
    (cond
     (seq results)
     results

     (< 100 count)
     (throw+ "Results not found after 100 iterations, giving up")

     :else
     (do
       (Thread/sleep 100)
       (recur (inc count) (f))))))

(defmacro block-until-results
  "Body is some expression that will be executed in a future. All
   errors from the body of the macro are ignored. Will block until
   results are returned from the body of the macro"
  [& body]
  `(future
     (block-until-results-fn
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
  (let [catalog-fut (block-until-results (json/parse-string (export/catalog-for-node "localhost" *port* node-name)))
        report-fut (block-until-results (export/reports-for-node "localhost" *port* node-name))
        facts-fut (block-until-results (export/facts-for-node "localhost" *port* node-name))]
    @catalog-fut
    @report-fut
    @facts-fut))

(deftest test-basic-roundtrip
  (let [facts {:name "foo.local"
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"}}
        export-out-file (testutils/temp-file "export-test" ".tar.gz")
        catalog (-> (get-in wire-catalogs [4 :empty])
                    (assoc :name "foo.local"))
        report (:basic reports)]

    (puppetdb-instance
     (fn []

       (is (empty? (export/get-nodes "localhost" *port*)))
       (submit-command :replace-catalog 4 catalog)
       (submit-command :store-report 3 (tur/munge-example-report-for-storage report))
       (submit-command :replace-facts 2 facts)

       (block-on-node (:name facts))

       (is (= (tuc/munge-catalog-for-comparison :v4 catalog)
              (tuc/munge-catalog-for-comparison :v4 (json/parse-string (export/catalog-for-node "localhost" *port* (:name catalog))))))

       (is (= (tur/munge-report-for-comparison (tur/munge-example-report-for-storage report))
              (tur/munge-report-for-comparison (-> (export/reports-for-node "localhost" *port* (:certname report))
                                                   first
                                                   tur/munge-example-report-for-storage))))
       (is (= facts (export/facts-for-node "localhost" *port* "foo.local")))

       (export/-main "--outfile" export-out-file "--host" "localhost" "--port" *port*)))

    (puppetdb-instance
     (fn []

       (is (empty? (export/get-nodes "localhost" *port*)))
       (import/-main "--infile" export-out-file "--host" "localhost" "--port" *port*)

       (block-on-node  (:name facts))

       (is (= (tuc/munge-catalog-for-comparison :v4 catalog)
              (tuc/munge-catalog-for-comparison :v4 (json/parse-string (export/catalog-for-node "localhost" *port* (:name catalog))))))
       (is (= (tur/munge-report-for-comparison (tur/munge-example-report-for-storage report))
              (tur/munge-report-for-comparison (-> (export/reports-for-node "localhost" *port* (:certname report))
                                                   first
                                                   tur/munge-example-report-for-storage))))
       (is (= facts (export/facts-for-node "localhost" *port* "foo.local")))))))

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

    (puppetdb-instance
     (fn []

       (import/-main "--infile" export-out-file "--host" "localhost" "--port" *port*)

       (block-on-node (:name facts))
       (Thread/sleep 5000)

       (is (= (tuc/munge-catalog-for-comparison :v3 catalog)
              (tuc/munge-catalog-for-comparison :v3 (->> (get-in catalog [:data :name])
                                                         (export/catalog-for-node "localhost" *port* :v3)
                                                         json/parse-string))))
       (is (= (tur/munge-report-for-comparison (-> report
                                                   (dissoc :environment)
                                                   tur/munge-example-report-for-storage))
              (tur/munge-report-for-comparison (-> (first (export/reports-for-node "localhost" *port* :v3 (:certname report)))
                                                   (update-in [:resource-events] vec)))))

       (is (= facts (export/facts-for-node "localhost" *port* :v3 "foo.local")))))))
