(ns puppetlabs.puppetdb.testutils
  (:import [java.io ByteArrayInputStream]
           [org.apache.activemq.broker BrokerService])
  (:require [puppetlabs.puppetdb.config :refer [default-mq-endpoint]]
            [puppetlabs.puppetdb.command :as dispatch]
            [puppetlabs.puppetdb.middleware
             :refer [wrap-with-puppetdb-middleware]]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.command :refer [command-app]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.logging :refer [reset-logging]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-log-output with-test-logging]]
            [slingshot.slingshot :refer [throw+]]
            [ring.mock.request :as mock]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :refer [parse-int excludes? keyset mapvals absolute-path]]
            [environ.core :refer [env]]
            [clojure.test :refer :all]
            [clojure.set :refer [difference]]
            [puppetlabs.puppetdb.test-protocols :as test-protos]))

(def c-t "application/json")

(defmacro dotestseq [bindings & body]
  (if-not (seq bindings)
    `(do ~@body)
    (let [case-versions (remove keyword? (take-nth 2 bindings))]
      `(doseq ~bindings
         (testing (str "Testing case " '~case-versions)
           ~@body)))))

(defmacro without-jmx
  "Disable ActiveMQ's usage of JMX. If you start two AMQ brokers in
  the same instance, their JMX beans will collide. Disabling JMX will
  allow them both to be started."
  [& body]
  `(with-redefs [puppetlabs.puppetdb.mq/enable-jmx (fn [broker# _#]
                                                     (.setUseJmx broker# false))
                 puppetlabs.puppetdb.jdbc/enable-jmx (fn [config# _#] nil)]
     (do ~@body)))

(defmacro with-test-broker
  "Evaluates body with a connection to an embedded MQ broker with the
  given name.  The broker and connection will only exist for the
  duration of the call.  Wrap with without-jmx to disable JMX."
  [name conn-var & body]
  `(with-log-output broker-logs#
     (let [dir#                   (absolute-path (fs/temp-dir "test-broker"))
           broker-name#           ~name
           conn-str#              (str "vm://" ~name)
           size-megs#              50
           ^BrokerService broker# (mq/build-embedded-broker
                                   broker-name#
                                   dir#
                                   {:store-usage size-megs#
                                    :temp-usage  size-megs#})]
       (.setPersistent broker# false)
       (mq/start-broker! broker#)
       (try
         (with-open [factory# (mq/activemq-connection-factory conn-str#)
                     ~conn-var (doto (.createConnection factory#) .start)]
           ~@body)
         (finally
           (mq/stop-broker! broker#)
           (fs/delete-dir dir#))))))

(def ^:dynamic *mq* nil)

(defn call-with-test-mq
  "Calls f after starting an embedded MQ broker that will be available
  for the duration of the call via *mq*.  JMX will be disabled."
  [f]
  (without-jmx
   (with-test-broker "test" connection
     (binding [*mq* {:connection connection}]
       (f)))))

(defmacro with-alt-mq [mq-name & body]
  `(with-redefs [default-mq-endpoint ~mq-name]
     (do ~@body)))

(defn call-counter
  "Returns a method that just tracks how many times it's called, and
  with what arguments. That information is stored in metadata for the
  method."
  []
  (let [ncalls    (ref 0)
        arguments (ref [])]
    (with-meta
      (fn [& args]
        (dosync
         (alter ncalls inc)
         (alter arguments conj args)))
      {:ncalls ncalls
       :args   arguments})))

(defn times-called
  "Returns the number of times a `call-counter` function has been
  invoked."
  [f]
  (deref (:ncalls (meta f))))

(defn args-supplied
  "Returns the argument list for each time a `call-counter` function
  has been invoked."
  [f]
  (deref (:args (meta f))))

(defn format-stacktrace
  "Given a `Throwable`, returns a String containing the message and stack trace.
  If passed `nil`, returns `nil`."
  [ex]
  (when ex (str (.getMessage ex) "\n" (string/join "\n" (.getStackTrace ex)))))

(defmacro with-fixtures
  "Evaluates `body` wrapped by the `each` fixtures of the current namespace."
  [& body]
  `(let [fixture-fn# (join-fixtures (:clojure.test/each-fixtures (meta ~*ns*)))]
     (fixture-fn# (fn [] ~@body))))

(defn json-content-type? [response]
  (= http/json-response-content-type (get-in response [:headers "Content-Type"])))

(defmacro is-equal-after
  "Checks equality of `args` after
   the `func` has been applied to them"
  [func & args]
  `(is (= ~@(map #(list func %) args))))

(defn assert-success!
  "Given a Ring response, verify that the status
  code is 200 OK.  If not, print the body and fail."
  [{:keys [status body] :as resp}]
  (when-not (= http/status-ok status)
    (println "ERROR RESPONSE BODY:\n" body)
    (is (= http/status-ok status))))

(defn get-request
  "Return a GET request against path, suitable as an argument to a ring
  app."
  ([path] (get-request path nil))
  ([path query] (get-request path query {}))
  ([path query params] (get-request path query params {"accept" c-t}))
  ([path query params headers]
     (let [request (mock/request :get path
                                 (if query
                                   (assoc params
                                     "query" (if (string? query) query (json/generate-string query)))
                                   params))
           orig-headers (:headers request)]
       (assoc request :headers (merge orig-headers headers)))))

(defn post-request
  "Submit a POST request against path, suitable as an argument to a ring
  app."
  ([path] (post-request path {}))
  ([path params] (post-request path params {"accept" c-t "content-type" c-t}))
  ([path params headers] (post-request path params headers nil))
  ([path params headers body]
     (let [request (mock/request :post path)
           orig-headers (:headers request)]
       (assoc request :headers (merge orig-headers headers)
              :content-type c-t
              :body body
              :params params))))

(defn query-request
  "Create a ring request map for a PuppetDB query. `http-method` indicates :get or :post.
  Parameters or headers are optional"
  ([http-method path query]
   (query-request http-method path query {}))
  ([http-method path query {:keys [params headers]}]
   (if (= :get http-method)
     (get-request path query params headers)
     (post-request path nil headers
                   (-> params
                       (assoc :query query)
                       json/generate-string
                       (.getBytes "UTF-8")
                       java.io.ByteArrayInputStream.)))))

(defn content-type
  "Returns the content type of the ring response"
  [resp]
  (get-in resp [:headers "Content-Type"]))

(defn paged-results*
   "Makes a ring request to `path` using the `app-fn` ring handler. Sets the necessary parameters
   for paged results.  Returns the ring response, with the body converted from the stream/JSON
  to clojure data structures."
   ([paged-test-params]
    (paged-results* :get paged-test-params))
   ([method {:keys [app-fn path query params limit total include_total offset] :as paged-test-params}]
    {:pre [(= #{} (difference
                   (keyset paged-test-params)
                   #{:app-fn :path :query :params :limit :total :include_total :offset}))]}
    (let [params  (merge params
                         {:limit limit
                          :offset offset})
          request (query-request method path query
                                 {:params
                                  (if include_total
                                    (assoc params :include_total true)
                                    params)})
          resp (app-fn request)
          body    (if (string? (:body resp))
                    (:body resp)
                    (slurp (:body resp)))]
      (assoc resp :body (json/parse-string body true)))))

(defn paged-results
  "This function makes multiple calls to the ring handler `app-fn` to consume all of the
   results for `query`, a `limit` number of records at a time using the built in paging
   functions. See paged-results* for the code making the GET requests, this function
  drives the pages and the assertions of the result."
  ([paged-test-params]
   (paged-results :get paged-test-params))
  ([method {:keys [app-fn path query params limit total include_total] :as paged-test-params}]
   {:pre [(= #{} (difference
                  (keyset paged-test-params)
                  #{:app-fn :path :query :pretty :params :limit :total :include_total}))]}
   (->> (range (java.lang.Math/ceil (/ total (float limit))))
        (mapcat (fn [n]
                  (let [req-params (assoc paged-test-params
                                          :offset (* limit n))
                        resp (paged-results* method req-params)
                        {:keys [status body headers]} resp]
                    (assert-success! resp)
                    (is (>= limit (count body)))
                    (if include_total
                      (do
                        (is (contains? headers paging/count-header))
                        (is (= total (parse-int (headers paging/count-header)))))
                      (is (excludes? headers paging/count-header)))
                    body))))))

(defn delete-on-exit
  "Will delete file `f` on shutdown of the JVM"
  [^java.io.File f]
  (doto f
    .deleteOnExit))

(def ^{:doc "Creates a temp file, deletes it on JVM shutdown"}
  temp-file (comp delete-on-exit fs/temp-file))

(def ^{:doc "Creates a temp directory, deletes the directory on JVM shutdown"}
  temp-dir (comp delete-on-exit (partial fs/temp-dir "tu-tmpdir")))

(defmacro with-err-str
  "Similar to with-out-str, but captures standard error rather than standard out"
  [& body]
  `(let [sw# (new java.io.StringWriter)]
     (binding [*err* sw#]
       ~@body
       (str sw#))))

(defn wrap-capture-args
  "Takes a function and wraps it, capturing each call's arguments by
   conjing them onto args"
  [orig-fn arg-atom]
  (fn [& args]
    (swap! arg-atom conj args)
    (apply orig-fn args)))

(defmacro with-wrapped-fn-args
  "with-wrapped-fn-args is a with-open style macro, where `bindings` is a vector where the
   odd elements are symbols and the even elements are functions.  The functions will be wrapped
   (see `wrap-capture-args`) and each of call's arguments will be stored in an atom bound to
   to the given symbol.

   (with-wrapped-fn-args [+-call-args +]
     (mapv + [1 2 3] [4 5 6])
     (println @+-call-args)
     (= '[(1 4) (2 5) (3 6)] @+-call-args))"
  [bindings & body]
  (cond
   (zero? (count bindings))
   `(do ~@body)

   (symbol? (first bindings))
   `(let [~(get bindings 0) (atom [])
          orig-fn# ~(get bindings 1)]
      (with-redefs [~(get bindings 1) (wrap-capture-args orig-fn# ~(get bindings 0))]
        (with-wrapped-fn-args ~(subvec bindings 2)
          ~@body)))
   :else (throw+ "with-wrapped-fn-args bindings should be pairs of count-atom-sym and fn-to-wrap with the call-count function")))

(defn uuid-in-response?
  "Returns true when the response contains a properly formed
   UUID in the body of the response"
  [response]
  (instance? java.util.UUID
             (-> response
                 :body
                 (json/parse-string true)
                 :uuid
                 java.util.UUID/fromString)))

(defn parse-result
  "Stringify (if needed) then parse the response"
  [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Throwable e
      body)))

(defn strip-hash
  [xs]
  (map #(dissoc % :hash) xs))

(defn select-keys'
  "Similar to clojure.core/select-keys, adds selected keys an empty
  instance of `map`, whereas clojure.core/select-keys will use an
  arraymap (and promote to hash-map). Passing in an ordered or sorted
  version of `map` will preserve order."
  [map keyseq]
  (loop [ret (empty map)
         keys (seq keyseq)]
    (if keys
      (let [entry (. clojure.lang.RT (find map (first keys)))]
        (recur
         (if entry
           (conj ret entry)
           ret)
         (next keys)))
      (with-meta ret (meta map)))))

(def select-values'
  "Like kitchensink.core/select-values but will preserve the order of the map
  if an orderd/sorted map is passed in"
  (comp vals select-keys'))

(defn block-until-results-fn
  "Executes `f`, if results are found, return them, otherwise
  wait and try again. Will throw an exception if results aren't found
  after 100 tries"
  [n f]
  (loop [count 0
         results (f)]
    (cond
     (seq results)
     results

     (< n count)
     (throw+ (format "Results not found after %d iterations, giving up" n))

     :else
     (do
       (Thread/sleep 100)
       (recur (inc count) (f))))))

(defmacro block-until-results
  "Body is some expression that will be executed in a future. All
  errors from the body of the macro are ignored. Will block until
  results are returned from the body of the macro"
  [n & body]
  `(future
     (block-until-results-fn
      ~n
      (fn []
        (try
          (do ~@body)
          (catch Exception e#
            ;; Ignore
            ))))))

(defmacro with-coordinated-fn
  "Redefines `function-to-coordinate` to block until `execute-it-sym`
  is invoked. One `execute-it-sym` is invoked, the original version of
  `function-to-coordinate` is invoked and execution of the code
  proceeds"
  [execute-it-sym function-to-coordinate & body]
  `(let [orig-fn# ~function-to-coordinate
         before# (promise)
         after# (promise)
         ~execute-it-sym (fn []
                           (deliver before# true)
                           @after#)]
     (with-redefs [~function-to-coordinate (fn [& args#]
                                             @before#
                                             (apply orig-fn# args#)
                                             (deliver after# true))]
       ~@body)))

(defn mock-fn
  "Create a mock version of a function that can tell you if it has been called."
  []
  (let [was-called (atom false)]
    (reify
      clojure.lang.IFn
      (invoke [_] (reset! was-called true))
      (invoke [_ _] (reset! was-called true))
      (invoke [_ _ _] (reset! was-called true))
      (invoke [_ _ _ _] (reset! was-called true))
      (invoke [_ _ _ _ _] (reset! was-called true))
      (invoke [_ _ _ _ _ _] (reset! was-called true))
      (invoke [_ _ _ _ _ _ _] (reset! was-called true))
      (invoke [_ _ _ _ _ _ _ _] (reset! was-called true))
      (invoke [_ _ _ _ _ _ _ _ _] (reset! was-called true))

      test-protos/IMockFn
      (called? [_] @was-called))))

(defn pprint-str
  "Pprints `x` to a string and returns that string"
  [x]
  (with-out-str (clojure.pprint/pprint x)))

(defn call-with-test-logging-silenced
  "A fixture to temporarily redirect all logging output to an atom, rather than
  to the usual ConsoleAppender.  Useful for tests that are intentionally triggering
  error conditions, to prevent them from cluttering up the test output with log
  messages."
  [f]
  (reset-logging)
  (with-test-logging
    (f)))

(def ^:dynamic *command-app* nil)

(defn call-with-command-app
  "A fixture to build a Command app and make it available as
  *command-app* within tests. This call should be nested within
  with-test-mq."
  ([f]
   (binding [*command-app* (wrap-with-puppetdb-middleware
                            (command-app
                             (fn [] {})
                             (partial #'dispatch/do-enqueue-raw-command
                                      (:connection *mq*)
                                      default-mq-endpoint)
                             (fn [] nil)
                             false
                             nil)
                            nil)]
     (f))))
