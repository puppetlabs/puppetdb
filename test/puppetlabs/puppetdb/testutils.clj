(ns puppetlabs.puppetdb.testutils
  (:import (org.apache.activemq.broker BrokerService))
  (:require [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.paging :as paging]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output]]
            [slingshot.slingshot :refer [throw+]]
            [ring.mock.request :as mock]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :refer [parse-int excludes? keyset mapvals]]
            [clojure.test :refer :all]
            [clojure.set :refer [difference]]))

(def c-t "application/json")

(defn test-db-config
  "This is a placeholder function; it is supposed to return a map containing
  the database configuration settings to use during testing.  We expect for
  it to be overridden by another definition from the test config file, so
  this implementation simply throws an exception that would indicate that our
  config file was invalid or not read properly."
  []
  (throw (IllegalStateException.
          (str "No test database configuration found!  Please make sure that "
               "your test config file defines a no-arg function named "
               "'test-db-config'."))))

(defn load-test-config
  "Loads the test configuration file from the classpath.  First looks for
  `config/local.clj`, and if that is not found, falls back to
  `config/default.clj`.

  Returns a map containing the test configuration.  Current keys include:

    :testdb-config-fn : a no-arg function that returns a hash of database
        settings, suitable for passing to the various `clojure.java.jdbc`
        functions."
  []
  (binding [*ns* (create-ns 'puppetlabs.puppetdb.testutils)]
    (try
      (load "/config/local")
      (catch java.io.FileNotFoundException ex
        (load "/config/default")))
    {:testdb-config-fn test-db-config}))

;; Memoize the loading of the test config file so that we don't have to
;; keep going back to disk for it.
(def test-config
  (memoize load-test-config))

(defn test-db
  "Return a map of connection attrs for the test database"
  []
  ((:testdb-config-fn (test-config))))

(defn drop-table!
  "Drops a table from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [table-name]
  (sql/do-commands
   (format "DROP TABLE IF EXISTS %s CASCADE" table-name)))

(defn drop-sequence!
  "Drops a sequence from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [sequence-name]
  (sql/do-commands
   (format "DROP SEQUENCE IF EXISTS %s" sequence-name)))

(defn clear-db-for-testing!
  "Completely clears the database, dropping all puppetdb tables and other objects
  that exist within it. Expects to be called from within a db binding.  You
  Exercise extreme caution when calling this function!"
  []
  (sql/do-commands "DROP SCHEMA IF EXISTS pdbtestschema CASCADE")
  (doseq [table-name (cons "test" (sutils/sql-current-connection-table-names))]
    (drop-table! table-name))
  (doseq [sequence-name (cons "test" (sutils/sql-current-connection-sequence-names))]
    (drop-sequence! sequence-name)))

(defmacro with-test-broker
  "Evaluates body with a connection to an embedded MQ broker with the
  given name.  The broker and connection will only exist for the
  duration of the call."
  [name conn-var & body]
  `(with-log-output broker-logs#
     (let [dir#                   (fs/absolute-path (fs/temp-dir "test-broker"))
           broker-name#           ~name
           conn-str#              (str "vm://" ~name)
           size-megs#              50
           ^BrokerService broker# (mq/build-embedded-broker
                                   broker-name#
                                   dir#
                                   {:store-usage size-megs#
                                    :temp-usage  size-megs#})]
       (.setUseJmx broker# false)
       (.setPersistent broker# false)
       (mq/start-broker! broker#)
       (try
         (with-open [factory# (mq/activemq-connection-factory conn-str#)
                     ~conn-var (doto (.createConnection factory#) .start)]
           ~@body)
         (finally
           (mq/stop-broker! broker#)
           (fs/delete-dir dir#))))))

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

(defn response-equal?
  "Test if the HTTP request is a success, and if the result is equal
  to the result of the form supplied to this method.  Arguments:

  `response`      - the HTTP response object.  This will be used to validate the
                    status code, and then the response body will be parsed as a JSON
                    string for comparison with the expected response.
  `expected`      - the expected result.  This should not be JSON-serialized as the
                    body of the HTTP response will be deserialized prior to comparison.
  `body-munge-fn` - optional.  If this is passed, it should be a function that will
                    be applied to the HTTP response body *after* JSON deserialization,
                    but before comparison with the expected result.  This can
                    be used to filter out fields that aren't relevant to the tests,
                    etc."
  ([response expected]
     (response-equal? response expected identity))
  ([response expected body-munge-fn]
     (is (= http/status-ok (:status response)))
     (is (= http/json-response-content-type (get-in response [:headers "Content-Type"])))
     (let [actual (when (:body response)
                    (-> (:body response)
                        (json/parse-string true)
                        (body-munge-fn)
                        (set)))]
       (is (= actual expected)
           (str response)))))

(defmacro =-after?
  "Checks equality of `args` after
   the `func` has been applied to them"
  [func & args]
  `(= ~@(map #(list func %) args)))

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
  ([path] (post-request path nil))
  ([path query] (post-request path query {}))
  ([path query params] (post-request path query params {"accept" c-t "content-type" c-t}))
  ([path query params headers] (post-request path query params headers nil))
  ([path query params headers body]
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
     (post-request path nil nil headers
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
                  #{:app-fn :path :query :params :limit :total :include_total}))]}
   (reduce
    (fn [coll n]
      (let [{:keys [status body headers] :as resp} (paged-results* method (assoc paged-test-params :offset (* limit n)))]
        (assert-success! resp)
        (is (>= limit (count body)))
        (if include_total
          (do
            (is (contains? headers paging/count-header))
            (is (= total (parse-int (headers paging/count-header)))))
          (is (excludes? headers paging/count-header)))
        (concat coll body)))
    []
    (range (java.lang.Math/ceil (/ total (float limit)))))))

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

(defmacro wrap-with-testing
  "If `version` is bound in this context, wrap the form in a testing
   macro to indicate the version being tested"
  [body]
  `(if ~(contains? &env 'version)
     (testing (str "Testing version " ~'version)
       ~@body)
     (do ~@body)))

(defmacro doverseq
  "Loose wrapper around `doseq` to support testing multiple versions of commands. Will run
   the test fixtures around each tested version and if `version` is chosen as the let bound
   variable to hold the current version being tested, with wrap it in a (testing...) block
   indicating the version being tested"
  [seq-exprs & body]
  `(let [each-fixture# (join-fixtures (:clojure.test/each-fixtures (meta ~*ns*)))]
     (doseq ~seq-exprs
       (each-fixture#
        (fn []
          (wrap-with-testing ~body))))))

(defn parse-result
  "Stringify (if needed) then parse the response"
  [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Throwable e
      body)))

(defmacro deftestseq
  "Def test wrapper around a doverseq."
  [name seq-exprs & body]
  (when *load-tests*
    `(def ~(vary-meta name assoc :test `(fn [] (doverseq ~seq-exprs ~@body)))
       (fn [] (test-var (var ~name))))))

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
