(ns com.puppetlabs.cmdb.cli.commandproc
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.command :as command]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.contrib.logging :as log]
            [clojure.java.jdbc :as sql]
            [clojure.data.json :as json]
            [clojure.contrib.duck-streams :as ds]
            [clamq.activemq :as activemq]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn])
  (:use [clojure.tools.cli :only (cli optional required group)]))

(defn load-from-mq
  [mq mq-endpoint db]
  (pl-utils/keep-going
   (fn [exception]
     (log/error "Error during command processing; reestablishing connection after 10s" exception)
     (Thread/sleep 10000))

   (with-open [conn (activemq/activemq-connection mq)]
     (command/process-commands! conn mq-endpoint {:db db}))))

(defn populate-mq
  [mq mq-endpoint dirname]
  (with-open [conn (activemq/activemq-connection mq)]
    (let [producer (mq-conn/producer conn)]
      (doseq [file (.listFiles (ds/file-str dirname))]
        (let [content (json/read-json (slurp file))
              msg {:command "replace catalog" :version 1 :payload content}
              msg (json/json-str msg)]
          (mq-producer/publish producer mq-endpoint msg)
          (log/info (format "Published %s" file)))))))

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
