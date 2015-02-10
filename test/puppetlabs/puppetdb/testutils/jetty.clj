(ns puppetlabs.puppetdb.testutils.jetty
  (:require [puppetlabs.puppetdb.testutils :refer [temp-dir temp-file]]
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
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as client]
            [clojure.string :as str]
            [fs.core :as fs]))

;; See utils.clj for more information about base-urls.
(def ^:dynamic *base-url* nil) ; Will not have a :version.

(defn log-config
  "Returns a logback.xml string with the specified `log-file` and `log-level`."
  [log-file log-level]
  (str "<configuration>

  <appender name=\"FILE\" class=\"ch.qos.logback.core.FileAppender\">
    <file>" log-file "</file>
    <append>true</append>
    <encoder>
      <pattern>%-4relative [%thread] %-5level %logger{35} - %msg%n</pattern>
    </encoder>
  </appender>

  <root level=\"" log-level "\">
    <appender-ref ref=\"FILE\" />
  </root>
</configuration>"))

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

(defn assoc-logging-config
  "Adds a dynamically created logback.xml with a test log. The
  generated log file name is returned for printing to the console."
  [config]
  (let [logback-file (fs/absolute-path (temp-file "logback" ".xml"))
        log-file (fs/absolute-path (temp-file "jett-test" ".log"))]
    (spit logback-file (log-config log-file "ERROR"))
    [log-file (assoc-in config [:global :logging-config] logback-file)]))

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
  `services` is a seq of additional services that should be started in addition
  to the core PuppetDB services. Binds *server* and *base-url* to refer to
  the instance."
  ([f] (puppetdb-instance (create-config) f))
  ([config f] (puppetdb-instance config [] f))
  ([config services f]
   (let [[log-file config] (-> config conf/adjust-tk-config assoc-logging-config)
         prefix (get-in config
                        [:web-router-service
                         :puppetlabs.puppetdb.cli.services/puppetdb-service])]
     (try
       (tkbs/with-app-with-config server
         (concat [jetty9-service puppetdb-service message-listener-service command-service webrouting-service metrics-service]
                 services)
         config
         (binding [*server* server
                   *base-url* (merge {:protocol "http"
                                      :host "localhost"
                                      :port (current-port server)}
                                     (when prefix {:prefix prefix}))]
           (f)))
       (finally
         (let [log-contents (slurp log-file)]
           (when-not (str/blank? log-contents)
             (utils/println-err "-------Begin PuppetDB Instance Log--------------------\n"
                                log-contents
                                "\n-------End PuppetDB Instance Log----------------------"))))))))

(defmacro with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance"
  [& body]
  `(puppetdb-instance
    (fn []
      ~@body)))

(defn current-queue-depth
  "Return the queue depth currently running PuppetDB instance (see `puppetdb-instance` for launching PuppetDB)"
  []
  (let [base-metrics-url (assoc *base-url* :prefix "/metrics" :version :v1)]
    (-> (str (utils/base-url->str base-metrics-url)
             "/mbeans/org.apache.activemq:BrokerName="
             (url-encode (:host base-metrics-url))
             ",Type=Queue,Destination=puppetlabs.puppetdb.commands")
        (client/get {:as :json})
        (get-in [:body :QueueSize]))))

(defn dispatch-count
  "Return the queue depth currently running PuppetDB instance (see `puppetdb-instance` for launching PuppetDB)"
  [dest-name]
  (let [base-metrics-url (assoc *base-url* :prefix "/metrics" :version :v1)]
    (-> (str (utils/base-url->str base-metrics-url)
             "/mbeans/org.apache.activemq:BrokerName="
             (url-encode (:host base-metrics-url))
             ",Type=Queue,Destination="
             dest-name)
        (client/get {:as :json})
        (get-in [:body :DispatchCount]))))

(defmacro without-jmx
  "Disable ActiveMQ's usage of JMX. If you start two AMQ brokers in
  the same instance, their JMX beans will collide. Disabling JMX will
  allow them both to be started."
  [& body]
  `(with-redefs [puppetlabs.puppetdb.mq/enable-jmx (fn [broker# _#]
                                                     (.setUseJmx broker# false))]
     (do ~@body)))

(defmacro with-command-endpoint
  "Invoke `body` with a different command endpoint (destination) name"
  [new-endpoint-name & body]
  `(binding [puppetlabs.puppetdb.cli.services/mq-endpoint ~new-endpoint-name]
     (do ~@body)))
