(ns puppetlabs.puppetdb.testutils.services
  (:refer-clojure :exclude [get])
  (:require [clj-time.core :as t]
            [clj-time.coerce :as time-coerce]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.testutils.log
             :refer [notable-pdb-event? with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [metrics.counters :refer [clear!]]
            [clojure.walk :as walk]
            [puppetlabs.trapperkeeper.app :as tk-app :refer [get-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-webservice]]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.cli.services :as svcs]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.command :refer [command-service] :as dispatch]
            [puppetlabs.puppetdb.http :refer [json-utf8-ctype?]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.utils :refer [base-url->str base-url->str-with-prefix]]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [throw+]]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.dashboard :refer [dashboard-redirect-service]]
            [puppetlabs.puppetdb.pdb-routing :refer [pdb-routing-service
                                                     maint-mode-service]]
            [puppetlabs.puppetdb.config :refer [config-service]]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.puppetdb.schema :as pls]
            [ring.util.response :as rr]))

;; See utils.clj for more information about base-urls.
(def ^:dynamic *base-url* nil) ; Will not have a :version.

(defn create-temp-config
  "Returns a config that refers to a temporary vardir."
  []
  {:nrepl {}
   :global {:vardir (testutils/temp-dir)}
   :puppetdb {:disable-update-checking "true"}
   :jetty {:port 0}
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
   #'command-service
   #'status-service
   #'scheduler-service
   #'metrics-webservice
   #'dashboard-redirect-service
   #'pdb-routing-service
   #'maint-mode-service
   #'config-service])

(defn clear-counters!
  [metrics]
  (walk/postwalk
    (fn [x] (if (= (type x)
                   com.codahale.metrics.Counter)
              (clear! x)
              x))
    metrics))

(defn strip-retired-config [config]
  (update config :database dissoc :classname :subprotocol))

(defn run-test-puppetdb [config services bind-attempts]
  (when (zero? bind-attempts)
    (throw (RuntimeException. "Repeated attempts to bind port failed, giving up")))
  (let [config (-> config
                   strip-retired-config
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
      (swap! dispatch/metrics clear-counters!)
      {:app (tkbs/bootstrap-services-with-config (map var-get services) config)
       :base-url base-url}

      (catch java.net.BindException e
        (log/errorf e "Error occured when Jetty tried to bind to port %s, attempt #%s"
                    port bind-attempts)
        (run-test-puppetdb config services (dec bind-attempts))))))

(defn call-with-puppetdb-instance
  "Stands up a puppetdb instance with the specified config, calls f,
  and then tears the instance down, binding *server* to the instance
  and *base-url* to the instance's URL during the execution of f.
  Starts any specified services in addition to the core PuppetDB
  services, and tries to bind to an HTTP port up to bind-attempts
  times (default 10) before giving up.  If a config is not specified,
  one will be generated by create-temp-config, using a database
  managed by with-test-db."
  ([f]
   (with-test-db
     (call-with-puppetdb-instance
      (assoc (create-temp-config) :database *db*)
      f)))
  ([config f] (call-with-puppetdb-instance config default-services f))
  ([config services f]
   (call-with-puppetdb-instance config services 10 f))
  ([config services bind-attempts f]
   (let [{:keys [app base-url]} (run-test-puppetdb config services bind-attempts)]
     (try
      (binding [*server* app
                *base-url* base-url]
        (f))
      (finally
        (tk-app/stop app))))))

(defn create-url-str [base-url url-suffix]
  (str (utils/base-url->str base-url)
       (when url-suffix
         url-suffix)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Functions that return URLs and strings for the top level services
;; in PDB
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pdb-query-url
  ([]
   (pdb-query-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/query" :version :v4)))

(defn query-url-str
  ([url-suffix]
   (query-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-query-url base-url) url-suffix)))

(defn pdb-cmd-url
  ([]
   (pdb-cmd-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/cmd" :version :v1)))

(defn cmd-url-str
  ([url-suffix]
   (cmd-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-cmd-url base-url) url-suffix)))

(defn pdb-admin-url
  ([]
   (pdb-admin-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/admin" :version :v1)))

(defn admin-url-str
  ([url-suffix]
   (admin-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-admin-url base-url) url-suffix)))

(defn pdb-metrics-url
  ([]
   (pdb-metrics-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/metrics" :version :v1)))

(defn metrics-url-str
  ([url-suffix]
   (metrics-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-metrics-url base-url) url-suffix)))

(defn pdb-meta-url
  ([]
   (pdb-meta-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/meta" :version :v1)))

(defn meta-url-str
  ([url-suffix]
   (meta-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-meta-url base-url) url-suffix)))

(defn root-url-str
  ([]
   (root-url-str *base-url*))
  ([base-url]
   (-> base-url
       (assoc :prefix "/")
       utils/base-url->str-with-prefix)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Basic HTTP functions for interacting with PuppetDB services, the
;; url-str arguments below will likely come from the above functions
;; relating to the various top level services
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(pls/defn-validated get-unparsed
  "Executes a GET HTTP request against `url-str`. `opts` are merged
  into the request map. The response is unparsed but returned as a
  string."
  [url-str :- String
   & [opts]]
  (http/get url-str
            (merge
             {:as :text
              :headers {"Content-Type" "application/json"}}
             opts)))

(pls/defn-validated get
  "Executes a GET HTTP request against `url-str`. `opts` are merged
  into the request map. JSON responses are automatically parsed before
  returning. Error responses are returned (i.e. 404) and not thrown."
  [url-str :- String
   & [opts]]
  (let [resp (get-unparsed url-str opts)
        ctype (rr/get-header resp "content-type")]
    (if (some-> ctype json-utf8-ctype?)
      (update resp :body #(json/parse-string % true))
      resp)))

(def default-ca-cert "test-resources/puppetserver/ssl/certs/ca.pem")
(def default-cert "test-resources/puppetserver/ssl/certs/localhost.pem")
(def default-ssl-key "test-resources/puppetserver/ssl/private_keys/localhost.pem")

(pls/defn-validated get-ssl
  "Executes a mutually authenticated GET HTTPS request against
  `url-str` using the above `get` function."
  [url-str & [opts]]
  (get url-str
       (merge {:ssl-ca-cert default-ca-cert
               :ssl-cert default-cert
               :ssl-key  default-ssl-key}
              opts)))

(pls/defn-validated get-or-throw
  "Same as `get` except will throw if an error status is returned."
  [url-str :- String
   & [opts]]
  (let [resp (get url-str opts)]
    (if (>= (:status resp) 400)
      (throw+ {:url url-str
               :response resp
               :status (:status resp)}
              (format "Failed request to '%s' with status '%s'" url-str (:status resp)))
      resp)))

(pls/defn-validated post
  "Executes a POST HTTP request against `url-str`. `body` is a clojure
  data structure that is converted to a JSON string before POSTing."
  [url-str :- String
   body
   & [opts]]
  (http/post url-str
             (merge
              {:body (json/generate-string body)
               :headers {"Content-Type" "application/json"}}
              opts)))

(pls/defn-validated post-ssl
  "Executes a mutually authenticated POST HTTP request against
  `url-str`. Uses the above `post` function"
  [url-str :- String
   body
   & [opts]]
  (post url-str
        body
        {:ssl-ca-cert default-ca-cert
         :ssl-cert default-cert
         :ssl-key  default-ssl-key}))

(defn certname-query
  "Returns a function that will query the given endpoint (`suffix`)
  for the provided `certname`"
  [base-url suffix certname]
  (-> (query-url-str base-url suffix)
      (get-or-throw {:query-params {"query" (json/generate-string [:= :certname certname])}})
      :body))

(defn get-reports
  ([certname]
   (get-reports *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/reports" certname)))

(defn get-factsets
  ([certname]
   (get-factsets *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/factsets" certname)))

(defn get-catalogs
  ([certname]
   (get-catalogs *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/catalogs" certname)))

(defn get-summary-stats []
  (-> (admin-url-str "/summary-stats")
      get-or-throw
      :body))

(defmacro with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance"
  [& body]
  `(with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
     (call-with-puppetdb-instance
      (fn []
        ~@body))))

(def ^:dynamic *notable-log-event?* notable-pdb-event?)

(defn call-with-single-quiet-pdb-instance
  "Calls the call-with-puppetdb-instance with args after suppressing
  all log events.  If there's an error or worse, prints the log to
  *err*.  Should not be nested, nor nested with calls to
  with-log-suppressed-unless-notable."
  [& args]
  (with-log-suppressed-unless-notable *notable-log-event?*
    (apply call-with-puppetdb-instance args)))

(defmacro with-single-quiet-pdb-instance
  "Convenience macro for call-with-single-quiet-pdb-instance."
  [& body]
  `(call-with-single-quiet-pdb-instance
    (fn [] ~@body)))

(defmacro with-pdb-with-no-gc [& body]
  `(with-test-db
     (call-with-single-quiet-pdb-instance
      (-> (svc-utils/create-temp-config)
          (assoc :database *db*)
          (assoc-in [:database :gc-interval] 0))
      (fn []
        ~@body))))

(def max-attempts 50)

(defn url-encode [s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn command-mbean-name
  "The full mbean name of the MQ destination used for commands"
  [host]
  (str "org.apache.activemq:type=Broker,brokerName=" (url-encode host)
       ",destinationType=Queue"
       ",destinationName=" conf/default-mq-endpoint))

(defn mq-mbeans-found?
  "Returns true if the ActiveMQ mbeans and the discarded command
  mbeans are found in `mbean-map`"
  [mbean-map]
  (let [mbean-names (map utils/kwd->str (keys mbean-map))]
    (and (some #(.startsWith % "org.apache.activemq") mbean-names)
         (some #(.startsWith % "puppetlabs.puppetdb.command") mbean-names)
         (some #(.startsWith % (command-mbean-name (:host *base-url*))) mbean-names))))

(defn metrics-up?
  "Returns true if the metrics endpoint (and associated jmx beans) are
  up, otherwise will continue to retry. Will fail after trying for
  roughly 5 seconds."
  []
  (loop [attempts 0]
    (let [{:keys [status body] :as response}
          (get (pdb-metrics-url "/mbeans"))]
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
  (-> (metrics-url-str (str "/mbeans/" (command-mbean-name (:host *base-url*))))
      get-or-throw
      :body))

(defn current-queue-depth
  "Returns current PuppetDB instance's queue depth."
  []
  (:QueueSize (queue-metadata)))

(defn discard-count
  "Returns the number of messages discarded from the command queue by
  the current PuppetDB instance."
  []
  (-> (pdb-metrics-url "/mbeans/puppetlabs.puppetdb.command:type=global,name=discarded")
      get-or-throw
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
  (-> (pdb-metrics-url (str "/mbeans" (command-mbean-name (:host *base-url*))))
      get-or-throw
      (get-in [:body "DispatchCount"])))

(defn sync-command-post
  "Syncronously post a command to PDB by blocking until the message is consumed
   off the queue."
  [base-url certname cmd version payload]
  (let [timeout-seconds 20]
    (let [response (pdb-client/submit-command-via-http!
                     base-url certname cmd version payload timeout-seconds)]
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
