;; ## Main entrypoint
;;
;; Grayskull consists of several, cooperating components:
;;
;; * Command processing
;;
;;   Grayskull uses a CQRS pattern for making changes to its domain
;;   objects (facts, catalogs, etc). Instead of simply submitting data
;;   to Grayskull and having it figure out the intent, the intent
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
;;   Refer to `com.puppetlabs.cmdb.command` for more details.
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
;;   All interaction with Grayskull is conducted via its REST API. We
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
(ns com.puppetlabs.cmdb.cli.services
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.scf.migrate :as migrations]
            [com.puppetlabs.cmdb.command :as command]
            [com.puppetlabs.cmdb.metrics :as metrics]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty]
            [com.puppetlabs.cmdb.http.server :as server]
            [clojure.java.jdbc :as sql])
  (:use [clojure.java.io :only [file]]
        [com.puppetlabs.utils :only (cli! ini-to-map)]
        [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; Grayskull components.

(def nthreads (+ 2 (.availableProcessors (Runtime/getRuntime))))
(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1")
(def mq-endpoint "com.puppetlabs.cmdb.commands")

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
  "Compact the indicated database every `interval` millis.

  This function doesn't terminate. If we encounter an exception during
  compaction, the operation will be retried after `interval` millis."
  [db interval]
  (pl-utils/keep-going
   (fn [exception]
     (log/error exception "Error during DB compaction"))

   (Thread/sleep interval)
   (log/info "Beginning database compaction")
   (sql/with-connection db
     (scf-store/garbage-collect!)
     (log/info "Finished database compaction"))))

(defn -main
  [& args]
  (let [[options _]    (cli! args
                             ["-c" "--config" "Path to config.ini"])
        config         (ini-to-map (:config options))

        db             (pl-jdbc/pooled-datasource (:database config))
        db-gc-interval (get (:database config) :gc-interval (* 1000 3600))
        web-opts       (get config :jetty {})
        mq-dir         (get-in config [:mq :dir])
        discard-dir    (file mq-dir "discarded")

        globals        {:scf-db db
                        :command-mq {:connection-string mq-addr
                                     :endpoint mq-endpoint}}
        ring-app       (server/build-app globals)]

    ;; Ensure the database is migrated to the latest version
    (sql/with-connection db
      (migrate!))

    (let [broker        (do
                          (log/info "Starting broker")
                          (mq/start-broker! (mq/build-embedded-broker mq-dir)))
          command-procs (do
                          (log/info (format "Starting %d command processor threads" nthreads))
                          (into [] (for [n (range nthreads)]
                                     (future
                                       (load-from-mq mq-addr mq-endpoint discard-dir db)))))
          web-app       (do
                          (log/info "Starting query server")
                          (future
                            (jetty/run-jetty ring-app web-opts)))
          db-gc         (do
                          (log/info "Starting database compactor")
                          (future
                            (db-garbage-collector db db-gc-interval)))]

      ;; Publish performance data via JMX
      (log/info "Starting JMX metrics publisher")
      (metrics/report-to-jmx)

      ;; Stop services by blocking on the completion of their futures
      (doseq [cp command-procs]
        (deref cp))
      (deref web-app)
      (deref db-gc)
      ;; Stop the mq the old-fashioned way
      (mq/stop-broker! broker))))
