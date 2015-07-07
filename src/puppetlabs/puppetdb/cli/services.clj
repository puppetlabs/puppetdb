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
            [puppetlabs.trapperkeeper.core :refer [defservice] :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [compojure.core :as compojure]
            [clojure.java.io :refer [file]]
            [clj-time.core :refer [ago]]
            [overtone.at-at :refer [mk-pool interspaced]]
            [slingshot.slingshot :refer [throw+ try+]]
            [puppetlabs.puppetdb.time :refer [to-seconds to-millis parse-period
                                              format-period period?]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate! indexes!]]
            [puppetlabs.puppetdb.meta.version :refer [version update-info]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.utils :as utils])
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
                                       (update-info update-server db)
                                       (catch Throwable e
                                         (log/debugf e "Could not retrieve update information (%s)" update-server)))]
    (when newer
      (log/info (cond-> (format "Newer version %s is available!" version)
                  link (str " Visit " link " for details."))))))

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
    (.close mq-connection)
    (.close mq-factory)
    (mq/stop-broker! broker)))

(defn stop-puppetdb
  "Shuts down PuppetDB, releasing resources when possible.  If this is
  not a normal shutdown, emergency? must be set, which currently just
  produces a fatal level level log message, instead of info."
  [context emergency?]
  (if emergency?
    (log/error "A fatal error occurred; shutting down all subsystems.")
    (log/info "Shutdown request received; puppetdb exiting."))
  (when-let [updater (context :updater)]
    (log/info "Shutting down updater thread.")
    (future-cancel updater))
  (shutdown-mq context)
  (when-let [ds (get-in context [:shared-globals :scf-write-db :datasource])]
    (.close ds))
  (when-let [ds (get-in context [:shared-globals :scf-read-db :datasource])]
    (.close ds))
  context)

(defn add-max-framesize
  "Add a maxFrameSize to broker url for activemq."
  [max-frame-size url]
  (format "%s&wireFormat.maxFrameSize=%s&marshal=true" url max-frame-size))

(defn- transfer-old-messages! [connection]
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
  it's deprecated, log and exit if it's unsupported. We do this in a
  single connection because HSQLDB seems to get confused if the
  database doesn't exist but we open and close a connection without
  creating anything."
  [db-conn-pool product-name]
  (sql/with-connection db-conn-pool
    (scf-store/validate-database-version #(System/exit 1))
    (migrate!)
    (indexes! product-name)))

(defn init-with-db
  "All initialization operations needing a database connection should
  happen here. This function creates a connection pool using
  `write-db-config` that will hang until it is able to make a
  connection to the database. This covers the case of the database not
  being fully started when PuppetDB starts. This connection pool will
  be opened and closed within the body of this function."
  [write-db-config product-name]
  (with-open [init-db-pool (pl-jdbc/make-connection-pool (assoc write-db-config
                                                           ;; Block waiting to grab a connection
                                                           :connection-timeout 0
                                                           ;; Only allocate connections when needed
                                                           :pool-availability-threshold 0))]
    (let [db-pool-map {:datasource init-db-pool}]
      (initialize-schema db-pool-map product-name))))

(defn start-puppetdb
  [context config service add-ring-handler get-route shutdown-on-error]
  {:pre [(map? context)
         (map? config)
         (ifn? add-ring-handler)
         (ifn? shutdown-on-error)]
   :post [(map? %)
          (every? (partial contains? %) [:broker])]}
  (let [{:keys [global   jetty
                database read-database
                puppetdb command-processing]} (conf/process-config! config)
        {:keys [product-name update-server
                vardir catalog-hash-debug-dir]} global
        {:keys [gc-interval    node-ttl
                node-purge-ttl report-ttl]} database
        {:keys [dlo-compression-threshold
                 max-frame-size threads]} command-processing
        {:keys [certificate-whitelist
                disable-update-checking]} puppetdb
        url-prefix (get-route service)
        write-db (pl-jdbc/pooled-datasource database)
        read-db (pl-jdbc/pooled-datasource (assoc read-database :read-only? true))
        mq-dir (str (file vardir "mq"))
        discard-dir (file mq-dir "discarded")
        mq-connection-str (add-max-framesize max-frame-size mq-addr)
        authorizer (when certificate-whitelist
                     (build-whitelist-authorizer certificate-whitelist))]

    (when-let [v (version)]
      (log/infof "PuppetDB version %s" v))

    (init-with-db database product-name)
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
          mq-factory (mq/activemq-connection-factory
                      (add-max-framesize max-frame-size mq-addr))
          mq-connection (doto (.createConnection mq-factory)
                          (.setExceptionListener
                           (reify ExceptionListener
                             (onException [this ex]
                               (log/error ex "service queue connection error"))))
                          .start)
          globals {:scf-read-db read-db
                   :scf-write-db write-db
                   :authorizer authorizer
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
                             (service-id service)
                             #(maybe-check-for-updates product-name update-server read-db)
                             #(stop-puppetdb % true))))]
      (transfer-old-messages! mq-connection)
      (let [app (->> (server/build-app globals)
                     (compojure/context url-prefix []))]
        (log/info "Starting query server")
        (add-ring-handler service app))

      ;; Pretty much this helper just knows our job-pool and gc-interval
      (let [job-pool (mk-pool)
            gc-interval-millis (to-millis gc-interval)
            gc-task #(interspaced gc-interval-millis % job-pool)
            seconds-pos? (comp pos? to-seconds)
            db-maintenance-tasks (fn []
                                   (do
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
        (gc-task #(compress-dlo! dlo-compression-threshold discard-dir)))
      (-> context
          (assoc :broker broker
                 :mq-factory mq-factory
                 :mq-connection mq-connection
                 :shared-globals globals)
          (merge (when updater {:updater updater}))))))

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
         (start-puppetdb context (get-config) this add-ring-handler get-route shutdown-on-error))

  (stop [this context]
        (stop-puppetdb context false))
  (shared-globals [this]
                  (:shared-globals (service-context this)))
  (query [this query-obj version query-expr paging-options row-callback-fn]
         (let [{db :scf-read-db url-prefix :url-prefix} (get (service-context this) :shared-globals)]
           (qeng/stream-query-result query-obj version query-expr paging-options db url-prefix row-callback-fn))))

(def ^{:arglists `([& args])
       :doc "Starts PuppetDB as a service via Trapperkeeper.  Aguments
        TK's normal config parsing to do a bit of extra
        customization."}
  -main
  (utils/wrap-main
   (fn [& args]
     (rh/add-hook #'puppetlabs.trapperkeeper.config/parse-config-data
                  #'conf/hook-tk-parse-config-data)
     (try+
      (apply tk/main args)
      (catch [:type ::conf/configuration-error] obj
        (log/error (:message obj))
        (throw+ (assoc obj ::utils/exit-status 1)))))))
