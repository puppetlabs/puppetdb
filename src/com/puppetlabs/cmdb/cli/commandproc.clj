(ns com.puppetlabs.cmdb.cli.commandproc
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.command :as command]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.contrib.logging :as log]
            [clojure.java.jdbc :as sql]
            [clamq.activemq :as activemq])
  (:use [clojure.tools.cli :only (cli optional required group)]))

(defn load-from-mq
  [mq mq-endpoint db]
  (pl-utils/keep-going
   (fn [exception]
     (log/error "Error during command processing; reestablishing connection after 10s" exception)
     (Thread/sleep 10000))

   (with-open [conn (activemq/activemq-connection mq)]
     (command/process-commands! conn mq-endpoint {:db db}))))

(defn -main
  [& args]
  (let [options     (cli args
                         (required ["-c" "--config" "Path to config.ini"]))
        config      (pl-utils/ini-to-map (:config options))

        db          (:database config)

        mq          (get-in config [:mq :connection_string])
        mq-endpoint (get-in config [:mq :endpoint])]

    (sql/with-connection db
      (scf-store/initialize-store))
    (load-from-mq mq mq-endpoint db)))
