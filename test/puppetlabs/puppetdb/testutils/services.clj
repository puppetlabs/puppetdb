(ns puppetlabs.puppetdb.testutils.services
  (:require [clj-time.core :as t]
            [clj-time.coerce :as time-coerce]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :refer [temp-dir temp-file]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.cli.services :as svcs]
            [puppetlabs.puppetdb.metrics :as metrics]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.mq-listener :refer [message-listener-service]]
            [puppetlabs.puppetdb.command :refer [command-service] :as dispatch]
            [puppetlabs.puppetdb.http.command :refer [puppetdb-command-service]]
            [puppetlabs.puppetdb.meta :refer [metadata-service]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as client]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [throw+]]
            [clojure.tools.logging :as log]
            [clojure.data.xml :as xml]
            [puppetlabs.puppetdb.dashboard :refer [dashboard-service dashboard-redirect-service]])
  (:import [ch.qos.logback.core Appender spi.LifeCycle]
           [ch.qos.logback.classic Level Logger]
           [org.slf4j LoggerFactory]))

;; See utils.clj for more information about base-urls.
(def ^:dynamic *base-url* nil) ; Will not have a :version.

;; Some useful knobs to control logging in your tests
(def ^:dynamic *log-level* "ERROR")
(def ^:dynamic *pdb-log-level* "ERROR")
(def ^:dynamic *log-levels* {}) ; a map like {"puppetlabs.puppetdb.command" "ERROR"}
(def ^:dynamic *extra-log-config* nil)
(def ^:dynamic *extra-appender-config* nil)

(defmacro with-log-level
  "Sets the (logback) log level for the logger specified by logger-id
  during the execution of body.  If logger-id is not a class, it is
  converted via str, and the level must be a clojure.tools.logging
  key, i.e. :info, :error, etc."
  [logger-id level & body]
  ;; Assumes use of logback (i.e. logger supports Levels).
  `(let [logger-id# ~logger-id
         logger-id# (if (class? logger-id#) logger-id# (str logger-id#))
         logger# (.getLogger (LoggerFactory/getILoggerFactory) logger-id#)
         original-level# (.getLevel logger#)]
     (.setLevel logger# (case ~level
                          :trace Level/TRACE
                          :debug Level/DEBUG
                          :info Level/INFO
                          :warn Level/WARN
                          :error Level/ERROR
                          :fatal Level/ERROR))
     (try
       (do ~@body)
       (finally (.setLevel logger# original-level#)))))

(defn create-log-appender-to-atom
  [destination-atom]
  ;; No clue yet if we're supposed to start with a default name.
  (let [name (atom (str "log-appender-to-atom-" (kitchensink/uuid)))]
    (reify
      Appender
      (doAppend [this event] (swap! destination-atom conj event))
      (getName [this] @name)
      (setName [this x] (reset! name x))
      LifeCycle
      (start [this] true)
      (stop [this] true))))

(defmacro with-logging-to-atom
  "Conjoins all logger-id events produced during the execution of the
  body to the destination atom, which must contain a collection.  If
  logger-id is not a class, it is converted via str."
  [logger-id destination & body]
  ;; Specify the root logger via org.slf4j.Logger/ROOT_LOGGER_NAME.
  `(let [logger-id# ~logger-id
         logger-id# (if (class? logger-id#) logger-id# (str logger-id#))
         logger# (.getLogger (LoggerFactory/getILoggerFactory) logger-id#)
         appender# (doto (create-log-appender-to-atom ~destination) .start)]
     (.addAppender logger# appender#)
     (try
       (do ~@body)
       (finally (.detachAppender logger# appender#)))))

(defn log-config
  "Returns a logback.xml string with the specified `log-file` `log-level`."
  [log-file]
  (-> [:configuration
       [:appender {:name "FILE" :class "ch.qos.logback.core.FileAppender"}
        [:file log-file]
        [:append true]
        [:encoder
         [:pattern "%-4relative [%thread] %-5level %logger{35} - %msg%n"]]
        *extra-appender-config*]

       (map (fn [[k v]] [:logger {:name k :level (name v)}])
            *log-levels*)
       [:logger {:name "puppetlabs.puppetdb" :level *pdb-log-level*}]
       *extra-log-config*

       [:root {:level *log-level*}
        [:appender-ref {:ref "FILE"}]]]
      xml/sexp-as-element
      xml/emit-str))

(defn create-db-map
  "Returns a database connection map with a reference to a new in memory HyperSQL database"
  []
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (str "mem:"
                     (java.util.UUID/randomUUID)
                     ";hsqldb.tx=mvcc;sql.syntax_pgs=true")})

(defn create-config
  "Creates a default config, populated with a temporary vardir and
  a fresh hypersql instance"
  []
  {:nrepl {}
   :global {:vardir (temp-dir)}
   :jetty {:port 0}
   :database (create-db-map)
   :command-processing {}})

(defn assoc-logging-config
  "Adds a dynamically created logback.xml with a test log. The
  generated log file name is returned for printing to the console."
  [config]
  (let [logback-file (fs/absolute-path (temp-file "logback" ".xml"))
        log-file (fs/absolute-path (temp-file "jett-test" ".log"))]
    (spit logback-file (log-config log-file))
    [log-file (assoc-in config [:global :logging-config] logback-file)]))

(defn open-port-num
  "Returns a currently open port number"
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn assoc-open-port
  "Updates `config` to include a (currently open) port number"
  [config]
  (let [port-key (if (-> config :jetty :ssl-port)
                   :ssl-port
                   :port)]
    (assoc-in config [:jetty port-key] (open-port-num))))

(def ^:dynamic *server*)

(defn puppetdb-instance
  "Stands up a puppetdb instance with `config`, tears down once `f` returns.
  `services` is a seq of additional services that should be started in
  addition to the core PuppetDB services. Binds *server* and
  *base-url* to refer to the instance. `attempts` indicates how many
  failed attempts should be made to bind to an HTTP port before giving
  up, defaults to 10"
  ([f] (puppetdb-instance (create-config) f))
  ([config f] (puppetdb-instance config [] f))
  ([config services f]
   (puppetdb-instance config services 10 f))
  ([config services attempts f]
   (when (zero? attempts)
     (throw (RuntimeException. "Repeated attempts to bind port failed, giving up")))
   (let [[log-file config] (-> config
                               conf/adjust-and-validate-tk-config
                               assoc-open-port
                               assoc-logging-config)
         port (or (get-in config [:jetty :port])
                  (get-in config [:jetty :ssl-port]))
         is-ssl (boolean (get-in config [:jetty :ssl-port]))
         base-url {:protocol (if is-ssl "https" "http")
                   :host "localhost"
                   :port port
                   :prefix "/pdb/query"
                   :version :v4}
         initial-drivers (sutils/get-registered-drivers)]
     (try
       (tkbs/with-app-with-config server
         (concat [jetty9-service
                  webrouting-service
                  svcs/puppetdb-service
                  message-listener-service
                  command-service
                  metrics/metrics-service
                  admin/admin-service
                  puppetdb-command-service
                  metadata-service
                  dashboard-service
                  dashboard-redirect-service]
                 services)
         config
         (binding [*server* server
                   *base-url* base-url]
           (f)))
       (catch java.net.BindException e
         (log/errorf e "Error occured when Jetty tried to bind to port %s, attempt #%s" port attempts)
         (puppetdb-instance config services (dec attempts) f))
       (finally
         (sutils/register-drivers! initial-drivers)
         (let [log-contents (slurp log-file)]
           (when-not (str/blank? log-contents)
             (utils/println-err
               "-------Begin PuppetDB Instance Log--------------------\n"
               log-contents
               "\n-------End PuppetDB Instance Log----------------------"))))))))

(defmacro with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance"
  [& body]
  `(puppetdb-instance
    (fn []
      ~@body)))

(def max-attempts 50)

(defn command-mbean-name
  "The full mbean name of the MQ destination used for commands"
  [host]
  (str "org.apache.activemq:type=Broker,brokerName=" (url-encode host)
       ",destinationType=Queue"
       ",destinationName=" svcs/mq-endpoint))

(defn mq-mbeans-found?
  "Returns true if the ActiveMQ mbeans and the discarded command
  mbeans are found in `mbean-map`"
  [mbean-map]
  (let [mbean-names (map utils/kwd->str (keys mbean-map))]
    (and (some #(.startsWith % "org.apache.activemq") mbean-names)
         (some #(.startsWith % "puppetlabs.puppetdb.command") mbean-names)
         (some #(.startsWith % (command-mbean-name (:host *base-url*))) mbean-names))))

(defn mbeans-url-str
  [base-url & [mbean]]
  (let [base-mbeans-url-str
        (-> base-url
            (assoc :prefix "/metrics" :version :v1)
            utils/base-url->str
            (str "/mbeans"))]
    (cond-> base-mbeans-url-str
      (seq mbean) (str "/" mbean))))

(defn metrics-up?
  "Returns true if the metrics endpoint (and associated jmx beans) are
  up, otherwise will continue to retry. Will fail after trying for
  roughly 5 seconds."
  []
  (loop [attempts 0]
    (let [{:keys [status body]:as response}
          (client/get (mbeans-url-str *base-url*) {:as :json :throw-exceptions false})]
      (cond
        (and (= 200 status)
             (mq-mbeans-found? body))
        true

        (<= max-attempts attempts)
        (throw+ response "JMX not up after %s attempts" attempts)

        :else
        (do (Thread/sleep 100)
            (recur (inc attempts)))))))

(defn queue-metadata
  "Return command queue metadata (from the `puppetdb-instance` launched PuppetDB)
   as a map:

  EnqueueCount - total number of messages sent to the queue since startup
  DequeueCount - total number of messages dequeued (ack'd by consumer) since startup
  InflightCount - number of messages sent to a consumer session but unacknowledged
  DispatchCount - total number of messages sent to consumer sessions (Dequeue + Inflight)
  ExpiredCount - number of messages that were not delivered because they were expired

  http://activemq.apache.org/how-do-i-find-the-size-of-a-queue.html"
  []
  ;; When starting up a `puppetdb-instance` there seems to be a brief
  ;; period of time that the server is up, the broker has been
  ;; started, but the JMX beans have not been initialized, so querying
  ;; for queue metrics fails, this check ensures it's started
  (-> (mbeans-url-str *base-url* (command-mbean-name (:host *base-url*)))
      (client/get {:as :json})
      :body))

(defn current-queue-depth
  "Return the queue depth currently running PuppetDB instance
   (see `puppetdb-instance` for launching PuppetDB)"
  []
  (:QueueSize (queue-metadata)))

(defn discard-count
  "Return the number of discarded messages from the command queue for the current
   running `puppetdb-instance`"
  []
  (-> (mbeans-url-str *base-url* "puppetlabs.puppetdb.command:type=global,name=discarded")
      (client/get {:as :json})
      (get-in [:body :Count])))

(defn until-consumed
  "Invokes `f` and blocks until the `num-messages` have been consumed
  from the commands queue. `num-messages` defaults to 1 if not
  provided. Returns the result of `f` if successful. Requires JMX to
  be enabled in ActiveMQ (the default, but `without-jmx` will cause
  this to fail).

  Exceptions thrown in the following cases:

  timeout - if the message isn't consumed in approximately 5 seconds
  new message in the DLO - if any message is discarded"
  ([f] (until-consumed 1 f))
  ([num-messages f]
   (metrics-up?)
   (let [{start-queue-depth :QueueSize
          start-committed-msgs :DequeueCount
          :as start-queue-metadata} (queue-metadata)
          start-discarded-count (discard-count)
          result (f)
          start-time (System/currentTimeMillis)]

     (loop [{curr-queue-depth :QueueSize
             curr-committed-msgs :DequeueCount
             :as curr-queue-metadata} (queue-metadata)
             curr-discarded-count (discard-count)
             attempts 0]

       (cond

        (< start-discarded-count curr-discarded-count)
        (throw+ {:original-queue-metadata start-queue-metadata
                 :original-discarded-count start-discarded-count
                 :current-queue-metadata curr-queue-metadata
                 :current-discarded-count curr-discarded-count}
                "Found %s new message(s) in the DLO"
                (- curr-discarded-count start-discarded-count))

        (= attempts max-attempts)
        (let [fail-time (System/currentTimeMillis)]
          (throw+ {:attempts max-attempts
                   :start-time start-time
                   :end-time fail-time}
                  "Failing after %s attempts and %s milliseconds"
                  max-attempts (- fail-time start-time)))

        (or (< 0 curr-queue-depth)
            (< curr-committed-msgs
               (+ start-committed-msgs num-messages)))
        (do
          (Thread/sleep 100)
          (recur (queue-metadata) (discard-count) (inc attempts)))

        :else
        result)))))

(defn dispatch-count
  "Return the queue depth currently running PuppetDB instance
   (see `puppetdb-instance` for launching PuppetDB)"
  [dest-name]
  (-> (mbeans-url-str *base-url* (command-mbean-name (:host *base-url*)))
      (client/get {:as :json})
      (get-in [:body :DispatchCount])))

(defn sync-command-post
  "Syncronously post a command to PDB by blocking until the message is consumed
   off the queue."
  [base-url cmd version payload]
  (until-consumed
   (fn []
     (pdb-client/submit-command-via-http! base-url cmd version payload))))

(defn wait-for-server-processing
  "Returns a truthy value indicating whether the wait was
  successful (i.e. didn't time out).  The current timeout granularity
  may be as bad as 10ms."
  [server timeout-ms]
  (let [now-ms #(time-coerce/to-long (t/now))
        deadline (+ (now-ms) timeout-ms)]
    (loop []
      (cond
        (> (now-ms) deadline)
        false

        (let [srv (get-service server :PuppetDBCommandDispatcher)
              stats (dispatch/stats srv)]
          (= (:executed-commands stats) (:received-commands stats)))
        true

        :else ;; In theory, polling might be avoided via watcher.
        (do
          (Thread/sleep 10)
          (recur))))))
