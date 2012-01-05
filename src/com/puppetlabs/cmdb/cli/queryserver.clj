(ns com.puppetlabs.cmdb.cli.queryserver
  (:require [clojure.contrib.logging :as log]
            [ring.adapter.jetty :as jetty]
            [com.puppetlabs.cmdb.query :as query]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.utils :as pl-utils])
  (:use [clojure.tools.cli :only (cli optional required group)]))

(defn -main
  [& args]
  (let [options     (cli args
                         (required ["-c" "--config" "Path to config.ini"]))
        config      (pl-utils/ini-to-map (:config options))

        db          (pl-jdbc/pooled-datasource (:database config))
        web-opts    (get config :jetty {})

        app         (query/build-app db)]

    (jetty/run-jetty app web-opts)))
