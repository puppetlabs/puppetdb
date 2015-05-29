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
  (:require [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.query.population :as pop]
            [puppetlabs.puppetdb.jdbc :as pl-jdbc]
            [puppetlabs.puppetdb.mq :as mq]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.kitchensink.core :as kitchensink]
            [robert.hooke :as rh]
            [puppetlabs.trapperkeeper.core :refer [defservice main]]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [compojure.core :as compojure]
            [clojure.java.io :refer [file]]
            [clj-time.core :refer [ago]]
            [overtone.at-at :refer [mk-pool interspaced]]
            [puppetlabs.puppetdb.time :refer [to-seconds to-millis parse-period format-period period?]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate! indexes!]]
            [puppetlabs.puppetdb.meta.version :as v]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng :as qeng])
  (:import [javax.jms ExceptionListener]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1&create=false")
(def mq-endpoint "puppetlabs.puppetdb.commands")

(defn auto-expire-nodes!
  "Expire nodes which haven't had any activity (catalog/fact submission)
  for more than `node-ttl`."
  [node-ttl db mq-connection]
  {:pre [(map? db)
         (period? node-ttl)]}
  (try
    (kitchensink/demarcate
     (format "sweep of stale nodes (threshold: %s)"
             (format-period node-ttl))
     (with-transacted-connection db
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
     (with-transacted-connection db
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
     (with-transacted-connection db
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

(defn check-for-updates
  "This will fetch the latest version number of PuppetDB and log if the system
  is out of date."
  [update-server db]
  (let [{:keys [version newer link]} (try
                                       (v/update-info update-server db)
                                       (catch Throwable e
                                         (log/debugf e "Could not retrieve update information (%s)" update-server)))]
    (when newer
      (log/info (cond-> (format "Newer version %s is available!" version)
                  link (str " Visit " link " for details."))))))

(defn maybe-check-for-updates
  "Check for updates if our `product-name` indicates we should, and skip the
  check otherwise."
  [puppet-enterprise? update-server db]
  (if-not puppet-enterprise?
    (check-for-updates update-server db)
    (log/debug "Skipping update check on Puppet Enterprise")))

(defn build-whitelist-authorizer
  "Build a function that will authorize requests based on the supplied
  certificate whitelist (see `cn-whitelist->authorizer` for more
  details). Returns :authorized if the request is allowed, otherwise a
  string describing the reason not."
  [whitelist]
  {:pre  [(string? whitelist)]
   :post [(fn? %)]}
  (let [allowed? (kitchensink/cn-whitelist->authorizer whitelist)]
    (fn [{:keys [ssl-client-cn] :as req}]
      (if (allowed? req)
        :authorized
        (do
          (log/warnf "%s rejected by certificate whitelist %s" ssl-client-cn whitelist)
          (format (str "The client certificate name (%s) doesn't "
                       "appear in the certificate whitelist. Is your "
                       "master's (or other PuppetDB client's) certname "
                       "listed in PuppetDB's certificate-whitelist file?")
                  ssl-client-cn))))))

(defn shutdown-mq
  "Explicitly shut down the queue `broker`"
  [{:keys [broker mq-factory mq-connection]}]
  (when broker
    (log/info "Shutting down the messsage queues.")
    (doseq [conn [mq-connection mq-factory]]
      (.close conn))
    (mq/stop-broker! broker)))

(defn shutdown-updater
  [{:keys [updater]}]
  (when updater
    (log/info "Shutting down updater thread.")
    (future-cancel updater)))

(defn shutdown-db-connections
  [{{read-db :scf-read-db
     write-db :scf-write-db} :shared-globals}]
  (doseq [db [write-db read-db]]
    (when-let [ds (:datasource db)] (.close ds))))

(defn stop-puppetdb
  "Shuts down PuppetDB, releasing resources when possible.  If this is
  not a normal shutdown, emergency? must be set, which currently just
  produces a fatal level level log message, instead of info."
  [context & [emergency?]]
  (if emergency?
    (log/error "A fatal error occurred; shutting down all subsystems.")
    (log/info "Shutdown request received; puppetdb exiting."))
  (shutdown-updater context)
  (shutdown-mq context)
  (shutdown-db-connections context)
  context)

(defn add-max-framesize
  "Add a maxFrameSize to broker url for activemq."
  [max-frame-size url]
  (format "%s&wireFormat.maxFrameSize=%s&marshal=true" url max-frame-size))

(defn start-puppetdb
  [context config url-prefix add-ring-handler shutdown-on-error]
  {:pre [(map? context)
         (map? config)
         (ifn? add-ring-handler)
         (ifn? shutdown-on-error)]
   :post [(map? %)
          (contains? % :broker)]}
  (let [{:keys [global   jetty
                database read-database
                puppetdb command-processing]} config
        {:keys [product-name update-server
                vardir catalog-hash-debug-dir]} global
        {:keys [gc-interval    node-ttl
                node-purge-ttl report-ttl]} database
        {:keys [dlo-compression-threshold
                 max-frame-size threads]} command-processing
        {:keys [certificate-whitelist
                disable-update-checking]} puppetdb
        write-db (pl-jdbc/pooled-datasource database)
        read-db (pl-jdbc/pooled-datasource read-database)
        puppet-enterprise? (not= product-name "puppetdb")]

    (when-let [v (v/version)]
      (log/infof "PuppetDB version %s" v))

    ;; Ensure the database is migrated to the latest version, and warn
    ;; if it's deprecated, log and exit if it's unsupported. We do
    ;; this in a single connection because HSQLDB seems to get
    ;; confused if the database doesn't exist but we open and close a
    ;; connection without creating anything.
    (sql/with-connection write-db
      (scf-store/validate-database-version #(System/exit 1))
      (migrate!)
      (indexes! puppet-enterprise?))

    ;; Initialize database-dependent metrics and dlo metrics if existent.
    (pop/initialize-metrics write-db)
    (let [mq-dir (-> (file vardir "mq") str)
          discard-dir (file mq-dir "discarded")]
      (when (.exists discard-dir)
        (dlo/create-metrics-for-dlo! discard-dir))
      (let [broker (try
                     (log/info "Starting broker")
                     (mq/build-and-start-broker! "localhost" mq-dir command-processing)
                     (catch java.io.EOFException e
                       (log/error
                        "EOF Exception caught during broker start, this "
                        "might be due to KahaDB corruption. Consult the "
                        "PuppetDB troubleshooting guide.")
                       (throw e)))
            mq-factory (->> mq-addr
                            (add-max-framesize max-frame-size)
                            mq/activemq-connection-factory)
            mq-connection (doto (.createConnection mq-factory)
                            (.setExceptionListener
                             (reify ExceptionListener
                               (onException [this ex]
                                 (log/error ex "service queue connection error"))))
                            .start)
            globals {:scf-read-db read-db
                     :scf-write-db write-db
                     :authorizer (some-> certificate-whitelist build-whitelist-authorizer)
                     :update-server update-server
                     :product-name product-name
                     :url-prefix url-prefix
                     :discard-dir (.getAbsolutePath discard-dir)
                     :mq-addr mq-addr
                     :mq-dest mq-endpoint
                     :mq-threads threads
                     :catalog-hash-debug-dir catalog-hash-debug-dir
                     :command-mq {:connection mq-connection
                                  :endpoint mq-endpoint}}
            updater (when-not disable-update-checking
                      (future (shutdown-on-error
                               (fn [] (maybe-check-for-updates puppet-enterprise? update-server read-db))
                               shutdown-updater)))]
        (do (log/info "Starting query server")
            (add-ring-handler (server/build-app globals)))

        ;; Pretty much this helper just knows our job-pool and gc-interval
        (let [job-pool (mk-pool)
              gc-interval-millis (to-millis gc-interval)
              gc-task #(interspaced gc-interval-millis % job-pool)
              db-maintenance-tasks (fn []
                                     (let [seconds-pos? (comp pos? to-seconds)]
                                       (when (seconds-pos? node-ttl) (auto-expire-nodes! node-ttl write-db mq-connection))
                                       (when (seconds-pos? node-purge-ttl) (purge-nodes! node-purge-ttl write-db)) 
                                       (when (seconds-pos? report-ttl) (sweep-reports! report-ttl write-db))
                                       ;; Order is important here to ensure
                                       ;; anything referencing an env or resource
                                       ;; param is purged first
                                       (garbage-collect! write-db)))]
          ;; Run database maintenance tasks seqentially to avoid
          ;; competition. Each task must handle its own errors.
          (gc-task db-maintenance-tasks)
          (gc-task (fn [] (compress-dlo! dlo-compression-threshold discard-dir))))
        (assoc context
               :broker broker
               :mq-factory mq-factory
               :mq-connection mq-connection
               :shared-globals globals
               :updater updater)))))

(defprotocol PuppetDBServer
  (shared-globals [this])
  (query [this query-obj version query-expr paging-options row-callback-fn]
    "Call `row-callback-fn' for matching rows.  The `paging-options' should
    be a map containing :order_by, :offset, and/or :limit."))

(defservice puppetdb-service
  "Defines a trapperkeeper service for PuppetDB; this service is responsible
  for initializing all of the PuppetDB subsystems and registering shutdown hooks
  that trapperkeeper will call on exit."
  PuppetDBServer
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:ShutdownService shutdown-on-error]]

  (start [this context]

         (let [config (-> (get-config)
                          conf/process-config!)
               url-prefix (get-route this)
               shutdown-on-error* (partial shutdown-on-error (service-id this))
               add-ring-handler* #(->> %
                                       (compojure/context url-prefix [])
                                       (add-ring-handler this))]
           (start-puppetdb context
                           config
                           url-prefix
                           add-ring-handler*
                           shutdown-on-error*)))

  (stop [this context] (stop-puppetdb context))
  (shared-globals [this] (:shared-globals (service-context this)))
  (query [this query-obj version query-expr paging-options row-callback-fn]
         (let [{db :scf-read-db url-prefix :url-prefix} (shared-globals this)]
           (qeng/stream-query-result query-obj version query-expr paging-options db url-prefix row-callback-fn))))

(defn -main
  "Calls the trapperkeeper main argument to initialize tk.

  For configuration customization, we intercept the call to parse-config-data
  within TK."
  [& args]
  (rh/add-hook #'puppetlabs.trapperkeeper.config/parse-config-data
               #'conf/hook-tk-parse-config-data)
  (apply main args))
