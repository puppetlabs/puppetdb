(ns puppetlabs.puppetdb.testutils.services
  (:require [clj-time.core :as t]
            [clj-time.coerce :as time-coerce]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :refer [temp-dir temp-file]]
            [puppetlabs.puppetdb.testutils.log
             :refer [notable-pdb-event? with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
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
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.config :as conf]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as client]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [throw+]]
            [clojure.tools.logging :as log]
            [clojure.data.xml :as xml]
            [puppetlabs.puppetdb.dashboard :refer [dashboard-redirect-service]]
            [puppetlabs.puppetdb.pdb-routing :refer [pdb-routing-service maint-mode-service]]))

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
   :command-processing {}})

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

(def default-services
  [#'jetty9-service
   #'webrouting-service
   #'svcs/puppetdb-service
   #'message-listener-service
   #'command-service
   #'metrics/metrics-service
   #'puppetdb-command-service
   #'dashboard-redirect-service
   #'pdb-routing-service
   #'maint-mode-service])

(defn call-with-puppetdb-instance
  "Stands up a puppetdb instance with `config`, tears down once `f` returns.
  `services` is a seq of additional services that should be started in
  addition to the core PuppetDB services. Binds *server* and
  *base-url* to refer to the instance. `attempts` indicates how many
  failed attempts should be made to bind to an HTTP port before giving
  up, defaults to 10."
  ([f] (call-with-puppetdb-instance (create-config) f))
  ([config f] (call-with-puppetdb-instance config default-services f))
  ([config services f]
   (call-with-puppetdb-instance config services 10 f))
  ([config services attempts f]
   (when (zero? attempts)
     (throw (RuntimeException. "Repeated attempts to bind port failed, giving up")))
   (let [config (-> config
                    conf/adjust-and-validate-tk-config
                    assoc-open-port)
         port (or (get-in config [:jetty :port])
                  (get-in config [:jetty :ssl-port]))
         is-ssl (boolean (get-in config [:jetty :ssl-port]))
         base-url {:protocol (if is-ssl "https" "http")
                   :host "localhost"
                   :port port
                   :prefix "/pdb/query"
                   :version :v4}]
     (try
       (tkbs/with-app-with-config server
         (map var-get services)
         config
         (binding [*server* server
                   *base-url* base-url]
           (f)))
       (catch java.net.BindException e
         (log/errorf e "Error occured when Jetty tried to bind to port %s, attempt #%s" port attempts)
         (call-with-puppetdb-instance config services (dec attempts) f))))))

(defmacro with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance"
  [& body]
  `(with-redefs [sutils/db-metadata (delay {:database "HSQL Database Engine"
                                            :version [2 2]})]
     (call-with-puppetdb-instance
       (fn []
         ~@body))))

(defn call-with-single-quiet-pdb-instance
  "Calls the call-with-puppetdb-instance with args after suppressing
  all log events.  If there's an error or worse, prints the log to
  *err*.  Should not be nested, nor nested with calls to
  with-log-suppressed-unless-notable."
  [& args]
  (with-log-suppressed-unless-notable notable-pdb-event?
    (apply call-with-puppetdb-instance args)))

(defmacro with-single-quiet-pdb-instance
  "Convenience macro for call-with-single-quiet-pdb-instance."
  [& body]
  `(call-with-single-quiet-pdb-instance
    (fn [] ~@body)))

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
  "Return command queue metadata from the current PuppetDB instance as
  a map:

  EnqueueCount - total number of messages sent to the queue since startup
  DequeueCount - total number of messages dequeued (ack'd by consumer) since startup
  InflightCount - number of messages sent to a consumer session but unacknowledged
  DispatchCount - total number of messages sent to consumer sessions (Dequeue + Inflight)
  ExpiredCount - number of messages that were not delivered because they were expired

  http://activemq.apache.org/how-do-i-find-the-size-of-a-queue.html"
  []
  ;; When starting up an instance via call-with-puppetdb-instance
  ;; there seems to be a brief period of time that the server is up,
  ;; the broker has been started, but the JMX beans have not been
  ;; initialized, so querying for queue metrics fails.  This check
  ;; ensures it has started.
  (-> (mbeans-url-str *base-url* (command-mbean-name (:host *base-url*)))
      (client/get {:as :json})
      :body))

(defn current-queue-depth
  "Returns current PuppetDB instance's queue depth."
  []
  (:QueueSize (queue-metadata)))

(defn discard-count
  "Returns the number of messages discarded from the command queue by
  the current PuppetDB instance."
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
  "Returns the dispatch count for the currently running PuppetDB instance."
  [dest-name]
  (-> (mbeans-url-str *base-url* (command-mbean-name (:host *base-url*)))
      (client/get {:as :json})
      (get-in [:body :DispatchCount])))

(defn sync-command-post
  "Syncronously post a command to PDB by blocking until the message is consumed
   off the queue."
  [base-url cmd version payload]
  (let [timeout-seconds 10]
    (let [response (pdb-client/submit-command-via-http! base-url cmd version payload timeout-seconds)]
      (if (>= (:status response) 400)
        (throw (ex-info "Command processing failed" {:response response}))
        response))))

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
