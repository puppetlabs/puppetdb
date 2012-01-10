(ns com.puppetlabs.cmdb.cli.populatemq
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [clojure.contrib.logging :as log]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [clojure.contrib.duck-streams :as ds]
            [clamq.activemq :as activemq]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map)]))


(defn populate-mq
  [mq mq-endpoint dirname]
  (with-open [conn (activemq/activemq-connection mq)]
    (let [producer (mq-conn/producer conn)]
      (doseq [file (.listFiles (ds/file-str dirname))]
        (let [content (json/parse-string (slurp file) true)
              msg {:command "replace catalog" :version 1 :payload content}
              msg (json/generate-string msg)]
          (mq-producer/publish producer mq-endpoint msg)
          (log/info (format "Published %s" file)))))))

(defn -main
  [& args]
  (let [[options _] (cli! args
                          ["-c" "--config" "Path to config.ini"]
                          ["-d" "--dir" "Directory containing catalogs"])
        config      (ini-to-map (:config options))

        db          (:database config)
        dir         (:dir config)

        mq          (get-in config [:mq :connection_string])
        mq-endpoint (get-in config [:mq :endpoint])]

    (sql/with-connection db
      (scf-store/initialize-store))
    (populate-mq mq mq-endpoint dir)))
