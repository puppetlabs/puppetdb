(ns puppetlabs.puppetdb.cli.services
  "Main entrypoint

   PuppetDB consists of several, cooperating components:

   * Command processing

     PuppetDB uses a CQRS pattern for making changes to its domain
     objects (facts, catalogs, etc). Instead of simply submitting data
     to PuppetDB and having it figure out the intent, the intent
     needs to explicitly be codified as part of the operation. This is
     known as a \"command\" (e.g. \"replace the current facts for node
     X\").

     Commands are processed asynchronously, however we try to do our
     best to ensure that once a command has been accepted, it will
     eventually be executed. Ordering is also preserved. To do this,
     all incoming commands are placed in a message queue which the
     command processing subsystem reads from in FIFO order.

     Refer to `puppetlabs.puppetdb.command` for more details.

   * Message queue

     We use stockpile to durably store commands. The \"in memory\"
     representation of that queue is a core.async channel.

   * REST interface

     All interaction with PuppetDB is conducted via its REST API. We
     embed an instance of Jetty to handle web server duties. Commands
     that come in via REST are relayed to the message queue. Read-only
     requests are serviced synchronously.

   * Database sweeper

     As catalogs are modified, unused records may accumulate and stale
     data may linger in the database. We periodically sweep the
     database, compacting it and performing regular cleanup so we can
     maintain acceptable performance."
  (:require [clj-time.core :refer [ago]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [metrics.counters :as counters :refer [counter inc!]]
            [metrics.gauges :refer [gauge-fn]]
            [metrics.timers :refer [time! timer]]
            [metrics.reporters.jmx :as jmx-reporter]
            [overtone.at-at :refer [mk-pool every interspaced stop-and-reset-pool!]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.meta.version :as version]
            [puppetlabs.puppetdb.metrics.core :as metrics
             :refer [metrics-registries]]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.query.population :as pop]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate! indexes!]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.time :refer [to-seconds to-millis parse-period
                                              format-period period?]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.core :refer [defservice] :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [robert.hooke :as rh]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.mq-listener :as mql]
            [puppetlabs.puppetdb.queue :as queue])
  (:import [javax.jms ExceptionListener]
           [java.util.concurrent.locks ReentrantLock]
           [org.joda.time Period]))

(def cli-description "Main PuppetDB daemon")
(def database-metrics-registry (get-in metrics/metrics-registries [:database :registry]))

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(defn auto-expire-nodes!
  "Expire nodes which haven't had any activity (catalog/fact submission)
  for more than `node-ttl`."
  [node-ttl db]
  {:pre [(map? db)
         (period? node-ttl)]}
  (try
    (kitchensink/demarcate
     (format "sweep of stale nodes (threshold: %s)"
             (format-period node-ttl))
     (jdbc/with-transacted-connection db
       (doseq [node (scf-store/expire-stale-nodes node-ttl)]
         (log/infof "Auto-expired node %s" node))))
    (catch Exception e
      (log/error e "Error while deactivating stale nodes"))))

(defn purge-nodes!
  "Delete nodes which have been *deactivated or expired* longer than
  `node-purge-ttl`."
  [node-purge-ttl db]
  {:pre [(map? db)
         (period? node-purge-ttl)]}
  (try
    (kitchensink/demarcate
     (format "purge deactivated and expired nodes (threshold: %s)"
             (format-period node-purge-ttl))
     (jdbc/with-transacted-connection db
       (scf-store/purge-deactivated-and-expired-nodes! (ago node-purge-ttl))))
    (catch Exception e
      (log/error e "Error while purging deactivated and expired nodes"))))

(defn sweep-reports!
  "Delete reports which are older than than `report-ttl`."
  [report-ttl db]
  {:pre [(map? db)
         (period? report-ttl)]}
  (try
    (kitchensink/demarcate
     (format "sweep of stale reports (threshold: %s)"
             (format-period report-ttl))
     (jdbc/with-transacted-connection db
       (scf-store/delete-reports-older-than! (ago report-ttl))))
    (catch Exception e
      (log/error e "Error while sweeping reports"))))

(defn garbage-collect!
  "Perform garbage collection on `db`, which means deleting any orphaned data.
  This basically just wraps the corresponding scf.storage function with some
  logging and other ceremony. Exceptions are logged but otherwise ignored."
  [db]
  {:pre [(map? db)]}
  (try
    (kitchensink/demarcate
      "database garbage collection"
      (scf-store/garbage-collect! db))
    (catch Exception e
      (log/error e "Error during garbage collection"))))

(def clean-options #{"expire_nodes" "purge_nodes" "purge_reports" "other"})

(defn clean-options->status [options]
  (str/join " " (map {"expire_nodes" "expiring-nodes"
                      "purge_nodes" "purging-nodes"
                      "purge_reports" "purging-reports"
                      "other" "other"}
                     (sort options))))

(def admin-metrics-registry
  (get-in metrics/metrics-registries [:admin :registry]))

(def clean-status (atom ""))

(def admin-metrics
  {;;"expiring-nodes" | "expiring-nodes purging-reports" | ...
   :cleaning (gauge-fn admin-metrics-registry ["cleaning"]
                       #(deref clean-status))

   :node-expirations (counter admin-metrics-registry "node-expirations")
   :node-purges (counter admin-metrics-registry "node-purges")
   :report-purges (counter admin-metrics-registry "report-purges")
   :other-cleans (counter admin-metrics-registry "other-cleans")

   :node-expiration-time (timer admin-metrics-registry ["node-expiration-time"])
   :node-purge-time (timer admin-metrics-registry ["node-purge-time"])
   :report-purge-time (timer admin-metrics-registry ["report-purge-time"])
   :other-clean-time (timer admin-metrics-registry ["other-clean-time"])})

(def clean-request-schema
  [(apply s/enum clean-options)])

(defn- clear-clean-status!
  "Clears the clean status (as a separate function to support tests)."
  []
  (reset! clean-status ""))

(defn-validated clean-up
  "Cleans up the resources specified by request, or everything if
  request is empty?."
  [db
   lock :- ReentrantLock
   {:keys [node-ttl node-purge-ttl report-ttl]} :- {:node-ttl Period
                                                    :node-purge-ttl Period
                                                    :report-ttl Period
                                                    s/Keyword s/Any}
   ;; Later, the values might be maps, i.e. {:limit 1000}
   request :- clean-request-schema]
  (when-not (.isHeldByCurrentThread lock)
    (throw (IllegalStateException. "cleanup lock is not already held")))
  (let [request (if (empty? request) clean-options (set request))
        status (clean-options->status request)]
    (try
      (reset! clean-status status)
      (when (request "expire_nodes")
        (time! (:node-expiration-time admin-metrics)
               (auto-expire-nodes! node-ttl db))
        (counters/inc! (:node-expirations admin-metrics)))
      (when (request "purge_nodes")
        (time! (:node-purge-time admin-metrics)
               (purge-nodes! node-purge-ttl db))
        (counters/inc! (:node-purges admin-metrics)))
      (when (request "purge_reports")
        (time! (:report-purge-time admin-metrics)
               (sweep-reports! report-ttl db))
        (counters/inc! (:report-purges admin-metrics)))
      ;; It's important that this go last to ensure anything referencing
      ;; an env or resource param is purged first.
      (when (request "other")
        (time! (:other-clean-time admin-metrics)
               (garbage-collect! db))
        (counters/inc! (:other-cleans admin-metrics)))
      (finally
        (clear-clean-status!)))))

(defn- clean-puppetdb
  "Implements the PuppetDBServer clean method, see the protocol for
  further information."
  [context config what]
  (let [lock (:clean-lock context)]
    (when (.tryLock lock)
      (try
        (clean-up (get-in context [:shared-globals :scf-write-db])
                  lock
                  (:database config)
                  what)
        true
        (finally
          (.unlock lock))))))

(defn maybe-check-for-updates
  [config read-db job-pool]
  (if (conf/foss? config)
    (let [checkin-interval-millis (* 1000 60 60 24)] ; once per day
      (every checkin-interval-millis #(-> config
                                          conf/update-server
                                          (version/check-for-updates! read-db))
             job-pool
             :desc "A reoccuring job to checkin the PuppetDB version"))
    (log/debug "Skipping update check on Puppet Enterprise")))

(defn stop-puppetdb
  "Shuts down PuppetDB, releasing resources when possible.  If this is
  not a normal shutdown, emergency? must be set, which currently just
  produces a fatal level level log message, instead of info."
  [context]
  (log/info "Shutdown request received; puppetdb exiting.")
  (when-let [ds (get-in context [:shared-globals :scf-write-db :datasource])]
    (.close ds))
  (when-let [ds (get-in context [:shared-globals :scf-read-db :datasource])]
    (.close ds))
  (when-let [pool (:job-pool context)]
    (stop-and-reset-pool! pool))
  (when-let [command-loader (:command-loader context)]
    (future-cancel command-loader))
  context)

(defn initialize-schema
  "Ensure the database is migrated to the latest version, and warn if
  it's deprecated, log and exit if it's unsupported.

  This function will return true iff any migrations were run."
  [db-conn-pool config]
  (jdbc/with-db-connection db-conn-pool
    (scf-store/validate-database-version #(System/exit 1))
    @sutils/db-metadata
    (let [migrated? (migrate! db-conn-pool)]
      (indexes! config)
      migrated?)))

(defn init-with-db
  "All initialization operations needing a database connection should
  happen here. This function creates a connection pool using
  `write-db-config` that will hang until it is able to make a
  connection to the database. This covers the case of the database not
  being fully started when PuppetDB starts. This connection pool will
  be opened and closed within the body of this function.

  This function will return true iff any migrations were run."
  [write-db-config config]
  (loop [db-spec (assoc write-db-config
                        ;; Block waiting to grab a connection
                        :connection-timeout 15000
                        :pool-name "PDBMigrationsPool")]
    (if-let [result
             (try
               (with-open [init-db-pool (jdbc/make-connection-pool db-spec
                                                                   database-metrics-registry)]
                 (let [db-pool-map {:datasource init-db-pool}]
                   (initialize-schema db-pool-map config)
                   ::success))
               (catch java.sql.SQLTransientConnectionException e
                 (log/error e "Error while attempting to create connection pool")))]
      result
      (recur db-spec))))

(defn start-puppetdb
  [context config service get-registered-endpoints]
  {:pre [(map? context)
         (map? config)]
   :post [(map? %)]}
  (let [{:keys [developer jetty
                database read-database
                puppetdb command-processing]} config
        {:keys [pretty-print max-enqueued]} developer
        {:keys [gc-interval]} database
        {:keys [disable-update-checking]} puppetdb

        write-db (jdbc/pooled-datasource (assoc database :pool-name "PDBWritePool")
                                         database-metrics-registry)
        read-db (jdbc/pooled-datasource (assoc read-database :read-only? true :pool-name "PDBReadPool")
                                        database-metrics-registry)]

    (when-let [v (version/version)]
      (log/infof "PuppetDB version %s" v))

    (init-with-db database config)

    (let [population-registry (get-in metrics/metrics-registries [:population :registry])]
      (pop/initialize-population-metrics! population-registry read-db))

    ;; Error handling here?
    (let [stockdir (conf/stockpile-dir config)
          command-chan (async/chan
                         (queue/sorted-command-buffer
                          max-enqueued
                          #(mql/update-counter! :invalidated %1 %2 inc!)))
          [q load-messages] (queue/create-or-open-stockpile (conf/stockpile-dir config))
          globals {:scf-read-db read-db
                   :scf-write-db write-db
                   :pretty-print pretty-print
                   :q q
                   :dlo (dlo/initialize (get-path stockdir "discard")
                                        (get-in metrics-registries
                                                [:dlo :registry]))
                   :command-chan command-chan}
          clean-lock (ReentrantLock.)
          command-loader (when load-messages
                           (future
                             (load-messages command-chan mql/inc-cmd-metrics)))]

      ;; Pretty much this helper just knows our job-pool and gc-interval
      (let [job-pool (mk-pool)
            gc-interval-millis (to-millis gc-interval)]
        (when-not disable-update-checking
          (maybe-check-for-updates config read-db job-pool))
        (when (pos? gc-interval-millis)
          (let [seconds-pos? (comp pos? to-seconds)
                what (filter identity
                             [(when-let [node-ttl (:node-ttl database)]
                                (when (seconds-pos? node-ttl)
                                  "expire_nodes"))
                              (when-let [node-purge-ttl (:node-purge-ttl database)]
                                (when (seconds-pos? node-purge-ttl)
                                  "purge_nodes"))
                              (when-let [report-ttl (:report-ttl database)]
                                (when (seconds-pos? report-ttl)
                                  "purge_reports"))
                              "other"])]
            (interspaced gc-interval-millis
                         (fn []
                           (.lock clean-lock)
                           (try
                             (try
                               (clean-up write-db clean-lock database what)
                               (catch Exception ex
                                 (log/error ex)))
                             (finally
                               (.unlock clean-lock))))
                         job-pool)))
        (assoc context
               :job-pool job-pool
               :shared-globals globals
               :clean-lock clean-lock
               :command-loader command-loader)))))

(defprotocol PuppetDBServer
  (shared-globals [this])
  (set-url-prefix [this url-prefix])
  (query [this version query-expr paging-options row-callback-fn]
    "Call `row-callback-fn' for matching rows.  The `paging-options' should
    be a map containing :order_by, :offset, and/or :limit.")
  (clean [this] [this what]
    "Performs maintenance.  If specified, what requests a subset of
    the normal operations, and must itself be a subset of
    #{\"expire_nodes\" \"purge_nodes\" \"purge_reports\" \"other\"}.
    If what is not specified or is empty, performs all maintenance.
    Returns false if some kind of maintenance was already in progress,
    true otherwise.  Although the latter does not imply that all of
    the operations were successful; consult the logs for more
    information."))

(defservice puppetdb-service
  "Defines a trapperkeeper service for PuppetDB; this service is responsible
  for initializing all of the PuppetDB subsystems and registering shutdown hooks
  that trapperkeeper will call on exit."
  PuppetDBServer
  [[:DefaultedConfig get-config]
   [:WebroutingService add-ring-handler get-registered-endpoints]]
  (init [this context]

        (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
          (jmx-reporter/start reporter))
        (assoc context :url-prefix (atom nil)))
  (start [this context]
         (start-puppetdb context (get-config) this get-registered-endpoints))

  (stop [this context]
        (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
          (jmx-reporter/stop reporter))
        (stop-puppetdb context))
  (set-url-prefix [this url-prefix]
                  (let [old-url-prefix (:url-prefix (service-context this))]
                    (when-not (compare-and-set! old-url-prefix nil url-prefix)
                      (throw+ {:url-prefix old-url-prefix
                               :new-url-prefix url-prefix
                               :type ::url-prefix-already-set}
                              (format "Attempt to set url-prefix to %s when it's already been set to %s" url-prefix @old-url-prefix)))))
  (shared-globals [this]
                  (:shared-globals (service-context this)))
  (query [this version query-expr paging-options row-callback-fn]
         (let [sc (service-context this)
               query-options (-> (get sc :shared-globals)
                                 (select-keys [:scf-read-db :warn-experimental])
                                 (assoc :url-prefix @(get sc :url-prefix)))]
           (qeng/stream-query-result version
                                     query-expr
                                     paging-options query-options
                                     row-callback-fn)))

  (clean [this] (clean this #{}))
  (clean [this what] (clean-puppetdb (service-context this) (get-config) what)))

(def ^{:arglists `([& args])
       :doc "Starts PuppetDB as a service via Trapperkeeper.  Aguments
        TK's normal config parsing to do a bit of extra
        customization."}
  -main
  (fn [& args]
    (rh/add-hook #'puppetlabs.trapperkeeper.config/parse-config-data
                 #'conf/hook-tk-parse-config-data)
    (apply tk/main args)))
