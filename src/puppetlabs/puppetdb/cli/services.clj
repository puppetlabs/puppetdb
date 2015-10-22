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

     We use an embedded instance of AciveMQ to handle queueing duties
     for the command processing subsystem. The message queue is
     persistent, and it only allows connections from within the same
     VM.

     Refer to `puppetlabs.puppetdb.mq` for more details.

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
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [overtone.at-at :refer [mk-pool interspaced]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.meta.version :as version]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.query.population :as pop]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate! indexes!]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.time :refer [to-seconds to-millis parse-period
                                              format-period period?]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.core :refer [defservice] :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [robert.hooke :as rh]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import [javax.jms ExceptionListener]))

(def cli-description "Main PuppetDB daemon")

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
       (doseq [node (scf-store/stale-nodes (ago node-ttl))]
         (log/infof "Auto-expiring node %s" node)
         (scf-store/expire-node! node))))
    (catch Exception e
      (log/error e "Error while deactivating stale nodes"))))

(defn purge-nodes!
  "Delete nodes which have been *deactivated or expired* longer than `node-purge-ttl`."
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

(defn compress-dlo!
  "Compresses discarded message which are older than `dlo-compression-threshold`."
  [dlo dlo-compression-threshold]
  (try
    (kitchensink/demarcate
     (format "compression of discarded messages (threshold: %s)"
             (format-period dlo-compression-threshold))
     (dlo/compress! dlo dlo-compression-threshold))
    (catch Exception e
      (log/error e "Error while compressing discarded messages"))))

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

(defn maybe-check-for-updates
  [config read-db]
  (if (conf/foss? config)
    (-> config
        conf/update-server
        (version/check-for-updates! read-db))
    (log/debug "Skipping update check on Puppet Enterprise")))

(defn shutdown-mq
  "Explicitly shut down the queue `broker`"
  [{:keys [broker mq-factory mq-connection]}]
  (when broker
    (log/info "Shutting down the messsage queues.")
    (mq/stop-broker! broker)))

(defn stop-puppetdb
  "Shuts down PuppetDB, releasing resources when possible.  If this is
  not a normal shutdown, emergency? must be set, which currently just
  produces a fatal level level log message, instead of info."
  [context]
  (log/info "Shutdown request received; puppetdb exiting.")
  (shutdown-mq context)
  (when-let [ds (get-in context [:shared-globals :scf-write-db :datasource])]
    (.close ds))
  (when-let [ds (get-in context [:shared-globals :scf-read-db :datasource])]
    (.close ds))
  context)

(defn- transfer-old-messages! [mq-endpoint]
  (let [[pending exists?]
        (try+
         [(mq/queue-size "localhost" "com.puppetlabs.puppetdb.commands") true]
         (catch [:type ::mq/queue-not-found] ex [0 false]))]
    (when (pos? pending)
      (log/infof "Transferring %d commands from legacy queue" pending)
      (let [n (mq/transfer-messages! "localhost"
                                     "com.puppetlabs.puppetdb.commands"
                                     mq-endpoint)]
        (log/infof "Transferred %d commands from legacy queue" n)))
    (when exists?
      (mq/remove-queue! "localhost" "com.puppetlabs.puppetdb.commands")
      (log/info "Removed legacy queue"))))

(defn initialize-schema
  "Ensure the database is migrated to the latest version, and warn if
  it's deprecated, log and exit if it's unsupported."
  [db-conn-pool config]
  (jdbc/with-db-connection db-conn-pool
    (scf-store/validate-database-version #(System/exit 1))
    @sutils/db-metadata
    (migrate! db-conn-pool)
    (indexes! config)))

(defn init-with-db
  "All initialization operations needing a database connection should
  happen here. This function creates a connection pool using
  `write-db-config` that will hang until it is able to make a
  connection to the database. This covers the case of the database not
  being fully started when PuppetDB starts. This connection pool will
  be opened and closed within the body of this function."
  [write-db-config config]
  (with-open [init-db-pool (jdbc/make-connection-pool
                            (assoc write-db-config
                                   ;; Block waiting to grab a connection
                                   :connection-timeout 0
                                   ;; Only allocate connections when needed
                                   :pool-availability-threshold 0))]
    (let [db-pool-map {:datasource init-db-pool}]
      (initialize-schema db-pool-map config))))

(defn start-puppetdb
  [context config service get-registered-endpoints]
  {:pre [(map? context)
         (map? config)]
   :post [(map? %)
          (every? (partial contains? %) [:broker])]}
  (let [{:keys [global   jetty
                database read-database
                puppetdb command-processing]} config
        {:keys [vardir]} global
        {:keys [gc-interval dlo-compression-interval node-ttl
                node-purge-ttl report-ttl]} database
        {:keys [dlo-compression-threshold]} command-processing
        {:keys [disable-update-checking]} puppetdb

        write-db (jdbc/pooled-datasource database)
        read-db (jdbc/pooled-datasource (assoc read-database :read-only? true))
        discard-dir (io/file (conf/mq-discard-dir config))]

    (when-let [v (version/version)]
      (log/infof "PuppetDB version %s" v))

    (init-with-db database config)
    (pop/initialize-metrics write-db)

    (when (.exists discard-dir)
      (dlo/create-metrics-for-dlo! discard-dir))
    (let [broker (try
                   (log/info "Starting broker")
                   (mq/build-and-start-broker! "localhost"
                                               (conf/mq-dir config)
                                               command-processing)
                   (catch java.io.EOFException e
                     (log/error
                      "EOF Exception caught during broker start, this "
                      "might be due to KahaDB corruption. Consult the "
                      "PuppetDB troubleshooting guide.")
                     (throw e)))
          globals {:scf-read-db read-db
                   :scf-write-db write-db}]
      (transfer-old-messages! (conf/mq-endpoint config))

      (when-not disable-update-checking
        (maybe-check-for-updates config read-db))

      ;; Pretty much this helper just knows our job-pool and gc-interval
      (let [job-pool (mk-pool)
            gc-interval-millis (to-millis gc-interval)
            dlo-compression-interval-millis (to-millis dlo-compression-interval)
            seconds-pos? (comp pos? to-seconds)
            db-maintenance-tasks (fn []
                                   (do
                                     (when (seconds-pos? node-ttl) (auto-expire-nodes! node-ttl write-db))
                                     (when (seconds-pos? node-purge-ttl) (purge-nodes! node-purge-ttl write-db))
                                     (when (seconds-pos? report-ttl) (sweep-reports! report-ttl write-db))
                                     ;; Order is important here to ensure
                                     ;; anything referencing an env or resource
                                     ;; param is purged first
                                     (garbage-collect! write-db)))]
        (when (pos? gc-interval-millis)
          ;; Run database maintenance tasks seqentially to avoid
          ;; competition. Each task must handle its own errors.
          (interspaced gc-interval-millis db-maintenance-tasks job-pool))
        (when (pos? dlo-compression-interval-millis)
          (interspaced dlo-compression-interval-millis
                       #(compress-dlo! dlo-compression-threshold discard-dir)
                       job-pool)))
      (assoc context
             :broker broker
             :shared-globals globals))))

(defprotocol PuppetDBServer
  (shared-globals [this])
  (set-url-prefix [this url-prefix])
  (query [this query-obj version query-expr paging-options row-callback-fn]
    "Call `row-callback-fn' for matching rows.  The `paging-options' should
    be a map containing :order_by, :offset, and/or :limit."))

(defservice puppetdb-service
  "Defines a trapperkeeper service for PuppetDB; this service is responsible
  for initializing all of the PuppetDB subsystems and registering shutdown hooks
  that trapperkeeper will call on exit."
  PuppetDBServer
  [[:DefaultedConfig get-config]
   [:WebroutingService add-ring-handler get-registered-endpoints]]
  (init [this context]
        (assoc context :url-prefix (atom nil)))
  (start [this context]
         (start-puppetdb context (get-config) this get-registered-endpoints))

  (stop [this context]
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
  (query [this query-obj version query-expr paging-options row-callback-fn]
         (let [{db :scf-read-db} (get (service-context this) :shared-globals)
               url-prefix @(get (service-context this) :url-prefix)]
           (qeng/stream-query-result query-obj version query-expr paging-options db url-prefix row-callback-fn))))

(def ^{:arglists `([& args])
       :doc "Starts PuppetDB as a service via Trapperkeeper.  Aguments
        TK's normal config parsing to do a bit of extra
        customization."}
  -main
  (fn [& args]
    (rh/add-hook #'puppetlabs.trapperkeeper.config/parse-config-data
                 #'conf/hook-tk-parse-config-data)
    (apply tk/main args)))
