(ns com.puppetlabs.cmdb.cli.queryserver
  (:require [clojure.contrib.logging :as log]
            [ring.adapter.jetty :as jetty]
            [com.puppetlabs.cmdb.query :as query]
            [com.puppetlabs.jdbc :as pl-jdbc])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map)]))

(defn -main
  [& args]
  (let [[options _] (cli! args
                          ["-c" "--config" "Path to config.ini"])
        config      (ini-to-map (:config options))

        db          (pl-jdbc/pooled-datasource (:database config))
        web-opts    (get config :jetty {})

        app         (query/build-app db)]

    (jetty/run-jetty app web-opts)))
