;; ## Main entrypoint
;;
;; PuppetDB consists of several, cooperating components:
;;
;; * Command processing
;;
;;   PuppetDB uses a CQRS pattern for making changes to its domain
;;   objects (facts, catalogs, etc). Instead of simply submitting data
;;   to PuppetDB and having it figure out the intent, the intent
;;   needs to explicitly be codified as part of the operation. This is
;;   known as a "command" (e.g. "replace the current facts for node
;;   X").
;;
;;   Commands are processed asynchronously, however we try to do our
;;   best to ensure that once a command has been accepted, it will
;;   eventually be executed. Ordering is also preserved. To do this,
;;   all incoming commands are placed in a message queue which the
;;   command processing subsystem reads from in FIFO order.
;;
;;   Refer to `com.puppetlabs.puppetdb.command` for more details.
;;
;; * Message queue
;;
;;   We use an embedded instance of AciveMQ to handle queueing duties
;;   for the command processing subsystem. The message queue is
;;   persistent, and it only allows connections from within the same
;;   VM.
;;
;;   Refer to `com.puppetlabs.mq` for more details.
;;
;; * REST interface
;;
;;   All interaction with PuppetDB is conducted via its REST API. We
;;   embed an instance of Jetty to handle web server duties. Commands
;;   that come in via REST are relayed to the message queue. Read-only
;;   requests are serviced synchronously.
;;
;; * Database sweeper
;;
;;   As catalogs are modified, unused records may accumulate and stale
;;   data may linger in the database. We periodically sweep the
;;   database, compacting it and performing regular cleanup so we can
;;   maintain acceptable performance.
;;
(ns com.puppetlabs.puppetdb.cli.services
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.command.dlo :as dlo]
            [com.puppetlabs.puppetdb.query.population :as pop]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.jetty :as jetty]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.http.server :as server])
  (:use [clojure.java.io :only [file]]
        [clj-time.core :only [ago secs minutes days]]
        [clojure.core.incubator :only (-?>)]
        [overtone.at-at :only (mk-pool interspaced)]
        [com.puppetlabs.time :only [to-secs to-millis parse-period format-period period?]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.utils :only (cli! configure-logging! inis-to-map with-error-delivery)]
        [com.puppetlabs.repl :only (start-repl)]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]
        [com.puppetlabs.puppetdb.version :only [version update-info]]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def configuration nil)
(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1&create=false")
(def mq-endpoint "com.puppetlabs.puppetdb.commands")
(def send-command! (partial command/enqueue-command! mq-addr mq-endpoint))

(defn load-from-mq
  "Process commands from the indicated endpoint on the supplied message queue.

  This function doesn't terminate. If we encounter an exception when
  processing commands from the message queue, we retry the operation
  after reopening a fresh connection with the MQ."
  [mq mq-endpoint discard-dir db]
  (pl-utils/keep-going
   (fn [exception]
     (log/error exception "Error during command processing; reestablishing connection after 10s")
     (Thread/sleep 10000))

   (with-open [conn (mq/connect! mq)]
     (command/process-commands! conn mq-endpoint discard-dir {:db db}))))

(defn auto-deactivate-nodes!
  "Deactivate nodes which haven't had any activity (catalog/fact submission)
  for more than `node-ttl`."
  [node-ttl db]
  {:pre [(map? db)
         (period? node-ttl)]}
  (try
    (pl-utils/demarcate
      (format "sweep of stale nodes (threshold: %s)"
              (format-period node-ttl))
      (with-transacted-connection db
        (doseq [node (scf-store/stale-nodes (ago node-ttl))]
          (send-command! (command-names :deactivate-node) 1 (json/generate-string node)))))
    (catch Exception e
      (log/error e "Error while deactivating stale nodes"))))

(defn purge-nodes!
  "Delete nodes which have been *deactivated* longer than `node-purge-ttl`."
  [node-purge-ttl db]
  {:pre [(map? db)
         (period? node-purge-ttl)]}
  (try
    (pl-utils/demarcate
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
    (pl-utils/demarcate
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
    (pl-utils/demarcate
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
    (pl-utils/demarcate
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

(defn configure-commandproc-threads
  "Update the supplied config map with the number of
  command-processing threads to use. If no value exists in the config
  map, default to half the number of CPUs. If only one CPU exists, we
  will use one command-processing thread."
  [config]
  {:pre  [(map? config)]
   :post [(map? %)
          (pos? (get-in % [:command-processing :threads]))]}
  (let [default-nthreads (-> (pl-utils/num-cpus)
                             (/ 2)
                             (int)
                             (max 1))]
    (update-in config [:command-processing :threads] #(or % default-nthreads))))

(defn configure-web-server
  "Update the supplied config map with information about the HTTP webserver to
  start. This will specify client auth, and add a default host/port
  http://puppetdb:8080 if none are supplied (and SSL is not specified)."
  [config]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (assoc-in config [:jetty :client-auth] :need))

(defn configure-gc-params
  "Helper function that munges the supported permutations of our GC-related
  `ttl` and interval settings (if present) from their config file
  representation to our internal representation as Period objects."
  [{:keys [database command-processing] :as config :or {database {}}}]
  {:pre  [(map? config)]
   :post [(map? %)
          (= (dissoc database :gc-interval :report-ttl :node-purge-ttl :node-ttl :node-ttl-days)
             (dissoc (:database %) :gc-interval :report-ttl :node-purge-ttl :node-ttl))
          (period? (get-in % [:command-processing :dlo-compression-threshold]))
          (every? period? (map (:database %) [:node-ttl :node-purge-ttl :report-ttl :gc-interval]))]}
  (let [maybe-parse-period #(-?> % parse-period)
        maybe-days #(-?> % days)
        maybe-minutes #(-?> % minutes)
        gc-interval-default (minutes 60)
        dlo-compression-default (days 1)
        ;; These defaults have to be actual periods rather than nil, because
        ;; the user could explicitly specify 0, and we want to treat that the
        ;; same
        node-ttl-default (secs 0)
        node-purge-ttl-default (secs 0)
        report-ttl-default (days 14)
        parsed-commandproc (update-in command-processing [:dlo-compression-threshold] #(or (maybe-parse-period %) dlo-compression-default))
        parsed-database (-> database
                            (update-in [:gc-interval] #(or (maybe-minutes %) gc-interval-default))
                            (update-in [:report-ttl] #(or (maybe-parse-period %) report-ttl-default))
                            (update-in [:node-purge-ttl] #(or (maybe-parse-period %) (secs 0)))
                            (update-in [:node-ttl] #(or (maybe-parse-period %) (maybe-days (:node-ttl-days database)) node-ttl-default))
                            (dissoc :node-ttl-days))]
    (assoc config :database parsed-database :command-processing parsed-commandproc)))

(defn configure-database
  "Update the supplied config map with information about the database. Adds a
  default hsqldb. If a single part of the database information is specified
  (such as classname but not subprotocol), no defaults will be filled in."
  [{:keys [database global] :as config}]
  {:pre  [(map? config)]
   :post [(map? config)]}
  (let [vardir       (:vardir global)
        default-db   {:classname   "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname     (format "file:%s;hsqldb.tx=mvcc;sql.syntax_pgs=true" (file vardir "db"))}]
    (assoc config :database (or database default-db))))

(defn normalize-product-name
  "Checks that `product-name` is specified as a legal value, throwing an
  exception if not. Returns `product-name` if it's okay."
  [product-name]
  {:pre [(string? product-name)]
   :post [(= (string/lower-case product-name) %)]}
  (let [lower-product-name (string/lower-case product-name)]
    (when-not (#{"puppetdb" "pe-puppetdb"} lower-product-name)
      (throw (IllegalArgumentException.
               (format "product-name %s is illegal; either puppetdb or pe-puppetdb are allowed" product-name))))
    lower-product-name))

(defn validate-vardir
  "Checks that `vardir` is specified, exists, and is writeable, throwing
  appropriate exceptions if any condition is unmet."
  [vardir]
  (if-let [vardir (file vardir)]
    (cond
     (not (.isAbsolute vardir))
     (throw (IllegalArgumentException.
             (format "Vardir %s must be an absolute path." vardir)))

     (not (.exists vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s does not exist. Please create it and ensure it is writable." vardir)))

     (not (.isDirectory vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not a directory." vardir)))

     (not (.canWrite vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not writable." vardir))))

    (throw (IllegalArgumentException.
            "Required setting 'vardir' is not specified. Please set it to a writable directory.")))
  vardir)

(defn set-global-configuration!
  "Store away global configuration"
  [config]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (def configuration config)
  config)

(defn parse-config!
  "Parses the given config file/directory and configures its various
  subcomponents.

  Also accepts an optional map argument 'initial-config'; if
  provided, any initial values in this map will be included
  in the resulting config map."
  ([path]
    (parse-config! path {}))
  ([path initial-config]
    {:pre [(string? path)
           (map? initial-config)]}
    (let [file (file path)]
      (when-not (.canRead file)
        (throw (IllegalArgumentException.
                (format "Configuration path '%s' must exist and must be readable." path)))))

    (-> (merge initial-config (inis-to-map path))
        (configure-logging!)
        (configure-commandproc-threads)
        (configure-web-server)
        (configure-database)
        (configure-gc-params)
        (set-global-configuration!))))

(defn on-shutdown
  "General cleanup when a shutdown request is received."
  []
  ;; nothing much to do here for now, but let's at least log that we're shutting down.
  (log/info "Shutdown request received; puppetdb exiting."))


(def supported-cli-options
  [["-c" "--config" "Path to config.ini or conf.d directory (required)"]
   ["-D" "--debug" "Enable debug mode" :default false :flag true]])

(def required-cli-options
  [:config])

(defn -main
  [& args]
  (let [[options _]                                (cli! args
                                                      supported-cli-options
                                                      required-cli-options)
        initial-config                             {:debug (:debug options)}
        {:keys [jetty database global command-processing]
            :as config}                            (parse-config! (:config options) initial-config)
        product-name                               (normalize-product-name (get global :product-name "puppetdb"))
        vardir                                     (validate-vardir (:vardir global))
        update-server                              (:update-server global "http://updates.puppetlabs.com/check-for-updates")
        resource-query-limit                       (get global :resource-query-limit 20000)
        ;; TODO: revisit the choice of 20000 as a default value for event queries
        event-query-limit                          (get global :event-query-limit 20000)
        db                                         (pl-jdbc/pooled-datasource database)
        gc-interval                                (get database :gc-interval)
        node-ttl                                   (get database :node-ttl)
        node-purge-ttl                             (get database :node-purge-ttl)
        report-ttl                                 (get database :report-ttl)
        dlo-compression-threshold                  (get command-processing :dlo-compression-threshold)
        mq-dir                                     (str (file vardir "mq"))
        discard-dir                                (file mq-dir "discarded")
        globals                                    {:scf-db               db
                                                    :command-mq           {:connection-string mq-addr
                                                                           :endpoint          mq-endpoint}
                                                    :resource-query-limit resource-query-limit
                                                    :event-query-limit    event-query-limit
                                                    :update-server        update-server
                                                    :product-name         product-name}]



    (when (version)
      (log/info (format "PuppetDB version %s" (version))))

    ;; Add a shutdown hook where we can handle any required cleanup
    (pl-utils/add-shutdown-hook! on-shutdown)

    ;; Ensure the database is migrated to the latest version, and warn if it's
    ;; deprecated. We do this in a single connection because HSQLDB seems to
    ;; get confused if the database doesn't exist but we open and close a
    ;; connection without creating anything.
    (sql/with-connection db
      (scf-store/warn-on-db-deprecation!)
      (migrate!))

    ;; Initialize database-dependent metrics
    (pop/initialize-metrics db)

    (let [error         (promise)
          broker        (try
                          (log/info "Starting broker")
                          (mq/build-and-start-broker! "localhost" mq-dir command-processing)
                          (catch java.io.EOFException e
                            (log/error
                              "EOF Exception caught during broker start, this "
                              "might be due to KahaDB corruption. Consult the "
                              "PuppetDB troubleshooting guide.")
                            (throw e)))
          command-procs (let [nthreads (command-processing :threads)]
                          (log/info (format "Starting %d command processor threads" nthreads))
                          (vec (for [n (range nthreads)]
                                 (future (with-error-delivery error
                                           (load-from-mq mq-addr mq-endpoint discard-dir db))))))
          updater       (future (maybe-check-for-updates product-name update-server db))
          web-app       (let [authorized? (if-let [wl (jetty :certificate-whitelist)]
                                            (pl-utils/cn-whitelist->authorizer wl)
                                            (constantly true))
                              app         (server/build-app :globals globals :authorized? authorized?)]
                          (log/info "Starting query server")
                          (future (with-error-delivery error
                                    (jetty/run-jetty app jetty))))
          job-pool      (mk-pool)]

      ;; Pretty much this helper just knows our job-pool and gc-interval
      (let [gc-interval-millis (to-millis gc-interval)
            gc-task #(interspaced gc-interval-millis % job-pool)
            db-maintenance-tasks [garbage-collect!
                                  (when (pos? (to-secs node-ttl))
                                    (partial auto-deactivate-nodes! node-ttl))
                                  (when (pos? (to-secs node-purge-ttl))
                                    (partial purge-nodes! node-purge-ttl))
                                  (when (pos? (to-secs report-ttl))
                                    (partial sweep-reports! report-ttl))]]

        (gc-task #(apply perform-db-maintenance! db (remove nil? db-maintenance-tasks)))
        (gc-task #(compress-dlo! dlo-compression-threshold discard-dir)))

      ;; Start debug REPL if necessary
      (let [{:keys [enabled type host port] :or {type "nrepl" host "localhost"}} (:repl config)]
        (when (= "true" enabled)
          (log/warn (format "Starting %s server on port %d" type port))
          (start-repl type host port)))

      (let [exception (deref error)]
        (doseq [cp command-procs]
          (future-cancel cp))
        (future-cancel updater)
        (future-cancel web-app)

        ;; Stop the mq the old-fashioned way
        (mq/stop-broker! broker)

        ;; Now throw the exception so the top-level handler will see it
        (throw exception)))))
