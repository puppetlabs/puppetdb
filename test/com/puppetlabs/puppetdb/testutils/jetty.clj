(ns com.puppetlabs.puppetdb.testutils.jetty
  (:require [com.puppetlabs.puppetdb.testutils :refer [temp-dir]]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.trapperkeeper.app :as tka]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [com.puppetlabs.puppetdb.cli.services :refer [puppetdb-service]]
            [clj-http.client :as client]))

(def ^:dynamic *port* nil)

(defn create-config
  "Creates a default config, populated with a temporary vardir and
   a fresh hypersql instance"
  []
  {:repl {}
   :global {:vardir (temp-dir)}
   :jetty {:port 0}
   :database (fixt/create-db-map)
   :command-processing {}})

(defn current-url
  "Uses the dynamically bound port to create a v4 URL to the
   currently running PuppetDB instance"
  ([] (current-url "/v4/"))
  ([suffix]
   (format "http://localhost:%s%s" *port* suffix)))

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

(defn puppetdb-instance
  "Stands up a puppetdb instance with `config`, tears down once `f` returns.
   If the port is assigned by Jetty, use *port* to get the currently running port."
  ([f] (puppetdb-instance (create-config) f))
  ([config f]
   (tkbs/with-app-with-config server
     [jetty9-service puppetdb-service]
     config
     (binding [*port* (current-port server)]
       (f)))))

(defmacro with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance"
  [& body]
  `(puppetdb-instance
    (fn []
      ~@body)))

(defn current-queue-depth
  "Return the queue depth currently running PuppetDB instance (see `puppetdb-instance` for launching PuppetDB)"
  []
  (-> (format "%smetrics/mbean/org.apache.activemq:BrokerName=localhost,Type=Queue,Destination=com.puppetlabs.puppetdb.commands" (current-url))
      (client/get {:as :json})
      (get-in [:body :QueueSize])))
