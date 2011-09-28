(ns com.puppetlabs.cmdb.core
  (:gen-class)
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
  (:use [clojure.contrib.command-line :only (with-command-line print-help)]))

;; TODO: externalize this into a configuration file
(def *db* {:classname "com.postgresql.Driver"
           :subprotocol "postgresql"
           :subname "//localhost:5432/cmdb"})

(def *db* {:classname "org.h2.Driver"
           :subprotocol "h2"
           :subname "mem:cmdb;DB_CLOSE_DELAY=-1"})

(def *mq* "tcp://localhost:61616")

(def *mq-endpoint* "com.puppetlabs.cmdb.commands")

(defn load-from-mq
  []
  (pl-utils/keep-going
   (fn [exception]
     (log/error "Error during command processing; reestablishing connection after 10s" exception)
     (Thread/sleep 10000))

   (with-open [conn (activemq/activemq-connection *mq*)]
     (command/process-commands! conn *mq-endpoint* {:db *db*}))))

(defn populate-mq
  [dirname]
  (with-open [conn (activemq/activemq-connection *mq*)]
    (let [producer (mq-conn/producer conn)]
      (doseq [file (.listFiles (ds/file-str dirname))]
        (let [content (slurp file)
              msg {:command "replace catalog" :version 1 :payload content}
              msg (json/json-str msg)]
          (mq-producer/publish producer *mq-endpoint* msg)
          (log/info (format "Published %s" file)))))))

(defn -main
  [dirname & args]
  (sql/with-connection *db*
    (scf-store/initialize-store))
  (future (populate-mq dirname))
  (load-from-mq))
