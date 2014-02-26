(ns com.puppetlabs.puppetdb.repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [com.puppetlabs.puppetdb.cli.services :refer [puppetdb-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]))

(def system nil)

(defn start []
  (alter-var-root #'system
                  (fn [_] (tk/boot-services-with-cli-data
                            [jetty9-service puppetdb-service]
                            {:config "/home/cprice/work/puppet/puppetdb/conf/config.ini"}))))

(defn stop []
  (alter-var-root #'system
                  (fn [s] (when s (tka/stop s)))))

(defn context []
  @(tka/app-context system))

(defn print-context []
  (clojure.pprint/pprint (context)))

(defn reset []
  (stop)
  (refresh :after 'com.puppetlabs.puppetdb.repl/start))