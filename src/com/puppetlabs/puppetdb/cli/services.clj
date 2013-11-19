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
            [com.puppetlabs.mq :as mq]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.http.server :as server]
            [com.puppetlabs.puppetdb.config :as conf]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.trapperkeeper.core :refer [defservice main]])
  (:use [clojure.java.io :only [file]]
        [clj-time.core :only [ago secs minutes days]]
        [overtone.at-at :only (mk-pool interspaced)]
        [com.puppetlabs.time :only [to-secs to-millis parse-period format-period period?]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.repl :only (start-repl)]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]
        [com.puppetlabs.puppetdb.version :only [version update-info]]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1&create=false")
(def mq-endpoint "com.puppetlabs.puppetdb.commands")
(def send-command! (partial command/enqueue-command! mq-addr mq-endpoint))

(defn load-from-mq
  "Process commands from the indicated endpoint on the supplied message queue.

  This function doesn't terminate. If we encounter an exception when
  processing commands from the message queue, we retry the operation
  after reopening a fresh connection with the MQ."
  [mq mq-endpoint discard-dir opt-map]
  {:pre [(:db opt-map)]}
  (kitchensink/keep-going
   (fn [exception]
     (log/error exception "Error during command processing; reestablishing connection after 10s")
     (Thread/sleep 10000))

   (with-open [conn (mq/connect! mq)]
     (command/process-commands! conn mq-endpoint discard-dir opt-map))))

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
          (send-command! (command-names :deactivate-node) 1 (json/generate-string node)))))
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

(defn on-shutdown
  "General cleanup when a shutdown request is received."
  [command-procs updater web-app broker]
  (log/info "Shutdown request received; puppetdb exiting.")
  (doseq [cp command-procs]
    (future-cancel cp))
  (future-cancel updater)
  (future-cancel web-app)

  ;; Stop the mq the old-fashioned way
  (mq/stop-broker! broker)
  (log/info "PuppetDB shutdown complete."))

(defservice puppetdb-service
  "Used to be -main"
  {:depends  [[:config-service get-config]
              [:webserver-service add-ring-handler join]
              [:shutdown-service shutdown-on-error]]
   :provides [shutdown]}
  (let [{:keys [jetty database read-database
                global command-processing] :as config}  (conf/process-config! (get-config))
        product-name                                    (normalize-product-name (get global :product-name "puppetdb"))
        update-server                                   (:update-server global "http://updates.puppetlabs.com/check-for-updates")
        ;; TODO: revisit the choice of 20000 as a default value for event queries
        event-query-limit                               (get global :event-query-limit 20000)
        write-db                                        (pl-jdbc/pooled-datasource database)
        read-db                                         (pl-jdbc/pooled-datasource (assoc read-database :read-only? true))
        gc-interval                                     (get database :gc-interval)
        node-ttl                                        (get database :node-ttl)
        node-purge-ttl                                  (get database :node-purge-ttl)
        report-ttl                                      (get database :report-ttl)
        dlo-compression-threshold                       (get command-processing :dlo-compression-threshold)
        mq-dir                                          (str (file (:vardir global) "mq"))
        discard-dir                                     (file mq-dir "discarded")
        globals                                         {:scf-read-db          read-db
                                                         :scf-write-db         write-db
                                                         :command-mq           {:connection-string mq-addr
                                                                                :endpoint          mq-endpoint}
                                                         :event-query-limit    event-query-limit
                                                         :update-server        update-server
                                                         :product-name         product-name}]

    (when (version)
      (log/info (format "PuppetDB version %s" (version))))

    ;; Ensure the database is migrated to the latest version, and warn if it's
    ;; deprecated. We do this in a single connection because HSQLDB seems to
    ;; get confused if the database doesn't exist but we open and close a
    ;; connection without creating anything.
    (sql/with-connection write-db
      (scf-store/warn-on-db-deprecation!)
      (migrate!))

    ;; Initialize database-dependent metrics
    (pop/initialize-metrics write-db)

    (let [broker        (try
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
                                 (future (shutdown-on-error
                                           #(load-from-mq
                                              mq-addr
                                              mq-endpoint
                                              discard-dir
                                              {:db write-db
                                               :catalog-hash-debug-dir (:catalog-hash-debug-dir global)}))))))
          updater       (future (maybe-check-for-updates product-name update-server read-db))
          web-app       (let [authorized? (if-let [wl (jetty :certificate-whitelist)]
                                            (build-whitelist-authorizer wl)
                                            (constantly true))
                              app         (server/build-app :globals globals :authorized? authorized?)]
                          (log/info "Starting query server")
                          (future (shutdown-on-error
                                    #(do
                                       (add-ring-handler app "")
                                       (join)))))
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

        (gc-task #(apply perform-db-maintenance! write-db (remove nil? db-maintenance-tasks)))
        (gc-task #(compress-dlo! dlo-compression-threshold discard-dir)))

      ;; Start debug REPL if necessary
      (let [{:keys [enabled type host port] :or {type "nrepl" host "localhost"}} (:repl config)]
        (when (kitchensink/true-str? enabled)
          (log/warn (format "Starting %s server on port %d" type port))
          (start-repl type host port)))

      ;; Return a map containing our trapperkeeper service functions, which in
      ;; our case is just a single shutdown function that it can use to clean
      ;; things up when the container is shutting down.
      {:shutdown (partial on-shutdown command-procs updater web-app broker)})))

(defn -main
  [& args]
  (apply main args))
