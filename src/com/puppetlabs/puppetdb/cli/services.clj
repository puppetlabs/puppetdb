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
;; * Database garbage collector
;;
;;   As catalogs are modified, unused records may accumulate in the
;;   database. We periodically compact the database to maintain
;;   acceptable performance.
;;
(ns com.puppetlabs.puppetdb.cli.services
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.scf.migrate :as migrations]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.query.population :as pop]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.jetty :as jetty]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl]
            [swank.swank :as swank]
            [com.puppetlabs.puppetdb.http.server :as server])
  (:use [clojure.java.io :only [file]]
        [clojure.tools.nrepl.transport :only (tty tty-greeting)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.utils :only (cli! configure-logging! ini-to-map)]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def configuration nil)
(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1")
(def mq-endpoint "com.puppetlabs.puppetdb.commands")

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

(defn db-garbage-collector
  "Compact the indicated database every `interval` minutes.

  This function doesn't terminate. If we encounter an exception during
  compaction, the operation will be retried after `interval` millis."
  [db interval]
  (pl-utils/keep-going
   (fn [exception]
     (log/error exception "Error during DB compaction"))

   (Thread/sleep (* 60 1000 interval))
   (log/info "Beginning database compaction")
   (with-transacted-connection db
     (scf-store/garbage-collect!)
     (log/info "Finished database compaction"))))

(defn configure-commandproc-threads
  "Update the supplied config map with the number of
  command-processing threads to use. If no value exists in the config
  map, default to half the number of CPUs."
  [config]
  {:pre [(map? config)]
   :post [(map? %)]}
  (let [default-nthreads (-> (Runtime/getRuntime)
                             (.availableProcessors)
                             (/ 2)
                             (int))]
    (update-in config [:command-processing :threads] #(or % default-nthreads))))

(defn configure-web-server
  "Update the supplied config map with information about the HTTP webserver to
  start. This will specify client auth, and add a default host/port
  http://puppetdb:8080 if none are supplied (and SSL is not specified)."
  [config]
  {:pre [(map? config)]
   :post [(map? config)]}
  (let [web-opts     (get config :jetty {})
        default-port (when-not (or (:ssl? web-opts) (:ssl-port web-opts))
                       8080)
        port         (get web-opts :port default-port)]
    (assoc config :jetty
           (-> web-opts
             (assoc :need-client-auth true)
             (assoc :port port)))))

(defn configure-database
  "Update the supplied config map with information about the database. Adds a
  default hsqldb and a gc-interval of 60 minutes. If a single part of the
  database information is specified (such as classname but not subprotocol), no
  defaults will be filled in."
  [config]
  {:pre [(map? config)]
   :post [(map? config)]}
  (let [default-db {:classname "org.hsqldb.jdbcDriver"
                    :subprotocol "hsqldb"
                    :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}
        db         (get config :database default-db)]
    (assoc config :database
           (merge {:gc-interval 60} db))))

(defn set-global-configuration!
  "Store away global configuration"
  [config]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (def configuration config)
  config)

(defn parse-config
  "Parses the given config file (if present) and configure its various
  subcomponents."
  [file]
  (let [config (if file (ini-to-map file) {})]
    (-> config
      (configure-logging!)
      (configure-commandproc-threads)
      (configure-web-server)
      (configure-database)
      (set-global-configuration!))))

(defn -main
  [& args]
  (let [[options _]   (cli! args)
        {:keys [jetty database mq] :as config} (parse-config (:config options))
        db            (pl-jdbc/pooled-datasource database)
        db-gc-minutes (get database :gc-interval 60)
        mq-dir        (get mq :dir "/var/lib/puppetdb/mq")
        discard-dir   (file mq-dir "discarded")
        globals       {:scf-db db
                       :command-mq {:connection-string mq-addr
                                    :endpoint mq-endpoint}}
        ring-app      (server/build-app globals)]

    ;; Ensure the database is migrated to the latest version
    (sql/with-connection db
      (migrate!))

    ;; Initialize database-dependent metrics
    (pop/initialize-metrics db)

    (let [broker        (do
                          (log/info "Starting broker")
                          (mq/start-broker! (mq/build-embedded-broker mq-dir)))
          command-procs (let [nthreads (get-in config [:command-processing :threads])]
                          (log/info (format "Starting %d command processor threads" nthreads))
                          (into [] (for [n (range nthreads)]
                                     (future
                                       (load-from-mq mq-addr mq-endpoint discard-dir db)))))
          web-app       (do
                          (log/info "Starting query server")
                          (future
                            (jetty/run-jetty ring-app jetty)))
          db-gc         (do
                          (log/info (format "Starting database compactor (%d minute interval)" db-gc-minutes))
                          (future
                            (db-garbage-collector db db-gc-minutes)))]

      ;; Start debug REPL if necessary
      (let [{:keys [enabled type host port] :or {type "nrepl" host "localhost"}} (:repl config)]
        (when (= "true" enabled)
          (log/warn (format "Starting %s server on port %d" type port))
          (cond
            (= type "nrepl")
            (nrepl/start-server :port port :transport-fn tty :greeting-fn tty-greeting)

            (= type "swank")
            (swank/start-server :host host :port port))))

      ;; Stop services by blocking on the completion of their futures
      (doseq [cp command-procs]
        (deref cp))
      (deref web-app)
      (deref db-gc)
      ;; Stop the mq the old-fashioned way
      (mq/stop-broker! broker))))
