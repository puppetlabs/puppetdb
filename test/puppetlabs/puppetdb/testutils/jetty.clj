(ns puppetlabs.puppetdb.testutils.jetty
  (:require [puppetlabs.puppetdb.testutils :refer [temp-dir]]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.trapperkeeper.app :as tka]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.puppetdb.cli.services :refer [puppetdb-service]]
            [puppetlabs.puppetdb.metrics :refer [metrics-service]]
            [puppetlabs.puppetdb.mq-listener :refer [message-listener-service]]
            [puppetlabs.puppetdb.command :refer [command-service]]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.config :as conf]
            [clj-http.util :refer [url-encode]]))

;; See utils.clj for more information about base-urls.
(def ^:dynamic *base-url* nil) ; Will not have a :version.

(defn create-config
  "Creates a default config, populated with a temporary vardir and
   a fresh hypersql instance"
  []
  {:nrepl {}
   :global {:vardir (temp-dir)}
   :jetty {:port 0}
   :database (fixt/create-db-map)
   :command-processing {}
   :web-router-service {:puppetlabs.puppetdb.cli.services/puppetdb-service ""
                        :puppetlabs.puppetdb.metrics/metrics-service "/metrics"}})

(defn current-port
  "Given a trapperkeeper server, return the port of the running jetty instance.
   Note there can be more than one port (i.e. SSL + non-SSL connector). This only
   returns the first one."
  [server]
  (-> @(tka/app-context server)
      (get-in [:WebserverService :jetty9-servers :default :server])
      .getConnectors
      first
      .getLocalPort))

(def ^:dynamic *server*)

(defn puppetdb-instance
  "Stands up a puppetdb instance with `config`, tears down once `f` returns.
   Adjusts *server* and *base-url* to refer to the instance."
  ([f] (puppetdb-instance (create-config) f))
  ([config f]
   (let [config (conf/adjust-tk-config config)
         prefix (get-in config
                        [:web-router-service
                         :puppetlabs.puppetdb.cli.services/puppetdb-service])]
     (tkbs/with-app-with-config server
       [jetty9-service puppetdb-service message-listener-service command-service webrouting-service metrics-service]
       config
       (binding [*server* server
                 *base-url* (merge {:protocol "http"
                                    :host "localhost"
                                    :port (current-port server)}
                                   (when prefix {:prefix prefix}))]
         (f))))))

(defmacro with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance"
  [& body]
  `(puppetdb-instance
    (fn []
      ~@body)))

(defn current-queue-depth
  "Return the queue depth currently running PuppetDB instance (see `puppetdb-instance` for launching PuppetDB)"
  []
  (let [base-metrics-url (assoc *base-url* :prefix "/metrics" :version :v1)
        url (str (utils/base-url->str base-metrics-url)
                 "/mbeans/org.apache.activemq:type=Broker,brokerName="
                 (url-encode (:host base-metrics-url))
                 ",destinationType=Queue"
                 ",destinationName=puppetlabs.puppetdb.commands")]
    (-> url
      (client/get {:as :json})
      (get-in [:body :QueueSize]))))
