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
            [puppetlabs.puppetdb.command :as command]
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
            [puppetlabs.puppetdb.time :refer [to-secs to-millis parse-period format-period period?]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate! indexes!]]
            [puppetlabs.puppetdb.version :refer [version update-info]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng :as qeng]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1&create=false")
(def ^:dynamic mq-endpoint "puppetlabs.puppetdb.commands")
(def send-command! (partial command/enqueue-command! mq-addr mq-endpoint))

(defn auto-deactivate-nodes!
  "Deactivate nodes which haven't had any activity (catalog/fact submission)
  for more than `node-ttl`."
  [node-ttl db]
  {:pre [(map? db)
         (period? node-ttl)]}
  (try
    (kitchensink/demarcate
     (format "sweep of stale nodes (threshold: %s)"
             (format-period node-ttl))
     (with-transacted-connection db
       (doseq [node (scf-store/stale-nodes (ago node-ttl))]
         (send-command! (command-names :deactivate-node) 2 node))))
    (catch Exception e
      (log/error e "Error while deactivating stale nodes"))))

(defn purge-nodes!
  "Delete nodes which have been *deactivated* longer than `node-purge-ttl`."
  [node-purge-ttl db]
  {:pre [(map? db)
         (period? node-purge-ttl)]}
  (try
    (kitchensink/demarcate
     (format "purge deactivated nodes (threshold: %s)"
             (format-period node-purge-ttl))
     (with-transacted-connection db
       (scf-store/purge-deactivated-nodes! (ago node-purge-ttl))))
    (catch Exception e
      (log/error e "Error while purging deactivated nodes"))))

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
     (with-transacted-connection db
       (scf-store/garbage-collect!)))
    (catch Exception e
      (log/error e "Error during garbage collection"))))

(defn perform-db-maintenance!
  "Runs the full set of database maintenance tasks in `fns` on `db` in order.
  Has no error-handling of its own, so all `fns` must handle their own errors.
  The purpose of this function is primarily to serialize these tasks, to avoid
  concurrent long-running database tasks."
  [db & fns]
  {:pre [(map? db)]}
  (doseq [f fns]
    (f db)))

(defn check-for-updates
  "This will fetch the latest version number of PuppetDB and log if the system
  is out of date."
  [update-server db]
  (let [{:keys [version newer link]} (try
                                       (update-info update-server db)
                                       (catch Throwable e
                                         (log/debug e (format "Could not retrieve update information (%s)" update-server))))
        link-str                     (if link
                                       (format " Visit %s for details." link)
                                       "")
        update-msg                   (format "Newer version %s is available!%s" version link-str)]
    (when newer
      (log/info update-msg))))

(defn maybe-check-for-updates
  "Check for updates if our `product-name` indicates we should, and skip the
  check otherwise."
  [product-name update-server db]
  (if (= product-name "puppetdb")
    (check-for-updates update-server db)
    (log/debug "Skipping update check on Puppet Enterprise")))

(defn build-whitelist-authorizer
  "Build a function that will authorize requests based on the supplied
  certificate whitelist (see `cn-whitelist->authorizer` for more
  details). Rejected requests are logged."
  [whitelist]
  {:pre  [(string? whitelist)]
   :post [(fn? %)]}
  (let [allowed? (kitchensink/cn-whitelist->authorizer whitelist)]
    (fn [{:keys [ssl-client-cn] :as req}]
      (if (allowed? req)
        true
        (do
          (log/warnf "%s rejected by certificate whitelist %s" ssl-client-cn whitelist)
          false)))))

(defn shutdown-mq-broker
  "Explicitly shut down the queue `broker`"
  [{:keys [broker]}]
  (when broker
    (log/info "Shutting down message broker.")
    ;; Stop the mq the old-fashioned way
    (mq/stop-broker! broker)))

(defn stop-puppetdb
  "Callback to execute any necessary cleanup during a normal shutdown."
  [context]
  (log/info "Shutdown request received; puppetdb exiting.")
  (shutdown-mq-broker context)
  context)

(defn error-shutdown!
  "Last-resort shutdown/cleanup code to execute when a fatal error has occurred."
  [context]
  (log/error "A fatal error occurred; shutting down all subsystems.")
  (when-let [updater (context :updater)]
    (log/info "Shutting down updater thread.")
    (future-cancel updater))

  (shutdown-mq-broker context))

(defn add-max-framesize
  "Add a maxFrameSize to broker url for activemq."
  [config url]
  (format "%s&wireFormat.maxFrameSize=%s&marshal=true" url (:max-frame-size config)))

(defn start-puppetdb
  [context config service add-ring-handler get-route shutdown-on-error]
  {:pre [(map? context)
         (map? config)
         (ifn? add-ring-handler)
         (ifn? shutdown-on-error)]
   :post [(map? %)
          (every? (partial contains? %) [:broker])]}
  (let [{:keys [jetty database read-database global command-processing puppetdb]
         :as config}                            (conf/process-config! config)
         product-name                               (:product-name global)
         update-server                              (:update-server global)
         url-prefix                                 (get-route service)
         write-db                                   (pl-jdbc/pooled-datasource database)
         read-db                                    (pl-jdbc/pooled-datasource (assoc read-database :read-only? true))
         gc-interval                                (get database :gc-interval)
         node-ttl                                   (get database :node-ttl)
         node-purge-ttl                             (get database :node-purge-ttl)
         report-ttl                                 (get database :report-ttl)
         dlo-compression-threshold                  (get command-processing :dlo-compression-threshold)
         mq-dir                                     (str (file (:vardir global) "mq"))
         discard-dir                                (file mq-dir "discarded")
         globals                                    {:scf-read-db          read-db
                                                     :scf-write-db         write-db
                                                     :command-mq           {:connection-string (add-max-framesize command-processing mq-addr)
                                                                            :endpoint          mq-endpoint}
                                                     :update-server        update-server
                                                     :product-name         product-name
                                                     :url-prefix           url-prefix
                                                     :discard-dir          (.getAbsolutePath discard-dir)
                                                     :mq-addr              mq-addr
                                                     :mq-dest              mq-endpoint
                                                     :mq-threads           (:threads command-processing)
                                                     :catalog-hash-debug-dir (:catalog-hash-debug-dir global)}]

    (when (version)
      (log/info (format "PuppetDB version %s" (version))))

    ;; Ensure the database is migrated to the latest version, and warn
    ;; if it's deprecated, log and exit if it's unsupported. We do
    ;; this in a single connection because HSQLDB seems to get
    ;; confused if the database doesn't exist but we open and close a
    ;; connection without creating anything.
    (sql/with-connection write-db
      (scf-store/validate-database-version #(System/exit 1))
      (migrate!)
      (indexes! (:product-name globals)))

    ;; Initialize database-dependent metrics and dlo metrics if existent.
    (pop/initialize-metrics write-db)
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
          context (assoc context :broker broker)
          updater (when-not (:disable-update-checking puppetdb)
                    (future (shutdown-on-error
                             (service-id service)
                             #(maybe-check-for-updates product-name update-server read-db)
                             error-shutdown!)))
          context (if updater
                    (assoc context :updater updater)
                    context)
          _       (let [authorized? (if-let [wl (:certificate-whitelist puppetdb)]
                                      (build-whitelist-authorizer wl)
                                      (constantly true))
                        app (server/build-app :globals globals :authorized? authorized?)]
                    (log/info "Starting query server")
                    (add-ring-handler service (compojure/context url-prefix [] app)))
          job-pool (mk-pool)]

      ;; Pretty much this helper just knows our job-pool and gc-interval
      (let [gc-interval-millis (to-millis gc-interval)
            gc-task #(interspaced gc-interval-millis % job-pool)
            db-maintenance-tasks [(when (pos? (to-secs node-ttl))
                                    (partial auto-deactivate-nodes! node-ttl))
                                  (when (pos? (to-secs node-purge-ttl))
                                    (partial purge-nodes! node-purge-ttl))
                                  (when (pos? (to-secs report-ttl))
                                    (partial sweep-reports! report-ttl))
                                  ;; Order is important here to ensure
                                  ;; anything referencing an env or resource
                                  ;; param is purged first
                                  garbage-collect!]]

        (gc-task #(apply perform-db-maintenance! write-db (remove nil? db-maintenance-tasks)))
        (gc-task #(compress-dlo! dlo-compression-threshold discard-dir)))

      (assoc context :shared-globals globals))))

(defprotocol PuppetDBServer
  (shared-globals [this])
  (query [this query-obj version query-expr paging-options row-callback-fn]
    "Call `row-callback-fn' for matching rows.  The `paging-options' should
    be a map containing :order_by, :offset, and/or :limit.")
  (submit-command [this command version payload]))

(defservice puppetdb-service
  "Defines a trapperkeeper service for PuppetDB; this service is responsible
  for initializing all of the PuppetDB subsystems and registering shutdown hooks
  that trapperkeeper will call on exit."
  PuppetDBServer
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:ShutdownService shutdown-on-error]]

  (start [this context]
         (start-puppetdb context (get-config) this add-ring-handler get-route shutdown-on-error))

  (stop [this context]
        (stop-puppetdb context))
  (shared-globals [this]
                  (:shared-globals (service-context this)))
  (query [this query-obj version query-expr paging-options row-callback-fn]
         (qeng/stream-query-result
          query-obj
          version
          query-expr
          paging-options
          (get-in (service-context this) [:shared-globals :scf-read-db])
          row-callback-fn))
  (submit-command [this command version payload]
                  (send-command! (command-names command) version payload)))

(defn -main
  "Calls the trapperkeeper main argument to initialize tk.

  For configuration customization, we intercept the call to parse-config-data
  within TK."
  [& args]
  (rh/add-hook #'puppetlabs.trapperkeeper.config/parse-config-data #'conf/hook-tk-parse-config-data)
  (apply main args))
