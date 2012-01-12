;; ## Main entrypoint
;;
;; Grayskull consists of several, cooperating components: a message
;; queue, a command processing subsystem, and a REST interface for use
;; by the outside world.
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
(ns com.puppetlabs.cmdb.cli.services
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.command :as command]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.contrib.logging :as log]
            [ring.adapter.jetty :as jetty]
            [com.puppetlabs.cmdb.query :as query]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map)]))

;; ### Wiring
;;
;; The following functions setup interaction between the main
;; Grayskull components.

(def *mq-addr* "vm://localhost")
(def *mq-endpoint* "com.puppetlabs.cmdb.commands")

(defn load-from-mq
  "Process commands from the indicated endpoint on the supplied message queue.

  This function doesn't terminate. If we encounter an exception when
  processing commands from the message queue, we retry the operation
  after reopening a fresh connection with the MQ."
  [mq mq-endpoint db]
  (pl-utils/keep-going
   (fn [exception]
     (log/error "Error during command processing; reestablishing connection after 10s" exception)
     (Thread/sleep 10000))

   (with-open [conn (mq/connect! mq)]
     (command/process-commands! conn mq-endpoint {:db db}))))

(defn -main
  [& args]
  (let [[options _] (cli! args
                          ["-c" "--config" "Path to config.ini"])
        config      (ini-to-map (:config options))

        db          (pl-jdbc/pooled-datasource (:database config))
        web-opts    (get config :jetty {})
        ring-app    (query/build-app db)
        mq-dir      (get-in config [:mq :dir])]

    ;; Initialize database schema
    ;;
    ;; TODO - Need to make this idempotent!
    (sql/with-connection db
      (scf-store/initialize-store))

    (let [broker            (do
                              (log/info "Starting broker")
                              (mq/start-broker! (mq/build-embedded-broker mq-dir)))
          command-processor (do
                              (log/info "Starting command processor")
                              (future
                                (load-from-mq *mq-addr* *mq-endpoint* db)))
          web-app           (do
                              (log/info "Starting query server")
                              (future
                                (jetty/run-jetty ring-app web-opts)))]

      ;; Stop the command-processor and web-app by blocking on
      ;; completion of their futures
      (deref command-processor)
      (deref web-app)
      ;; Stop the mq the old-fashioned way
      (mq/stop-broker! broker))))
