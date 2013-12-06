(ns com.puppetlabs.puppetdb.testutils
  (:import (org.apache.activemq.broker BrokerService))
  (:require [com.puppetlabs.mq :as mq]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [fs.core :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output]])
  (:use     [com.puppetlabs.puppetdb.scf.storage-utils :only [sql-current-connection-table-names]]
            [puppetlabs.kitchensink.core :only [parse-int excludes? keyset]]
            [clojure.test]
            [clojure.set :only [difference]]
            [ring.mock.request]))

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
  (binding [*ns* (create-ns 'com.puppetlabs.puppetdb.testutils)]
    (try
      (load "/config/local")
      (catch java.io.FileNotFoundException ex
          (load "/config/default")))
    {
      :testdb-config-fn test-db-config
    }))

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

(defn clear-db-for-testing!
  "Completely clears the database, dropping all puppetdb tables and other objects
  that exist within it.  Expects to be called from within a db binding.  You
  Exercise extreme caution when calling this function!"
  []
  (doseq [table-name (cons "test" (sql-current-connection-table-names))] (drop-table! table-name)))

(defmacro with-test-broker
  "Constructs and starts an embedded MQ, and evaluates `body` inside a
  `with-open` expression that takes care of connection cleanup and MQ
  tear-down.

  `name` - The name to use for the embedded MQ

  `conn-var` - Inside of `body`, the variable named `conn-var`
  contains an active connection to the embedded broker.

  Example:

      (with-test-broker \"my-broker\" the-connetion
        ;; Do something with the connection
        (prn the-connection))
  "
  [name conn-var & body]
  `(with-log-output broker-logs#
    (let [dir#                   (fs/absolute-path (fs/temp-dir))
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
         (with-open [~conn-var (mq/connect! conn-str#)]
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
  (if ex (str (.getMessage ex) "\n" (string/join "\n" (.getStackTrace ex)))))

(defmacro with-fixtures
  "Evaluates `body` wrapped by the `each` fixtures of the current namespace."
  [& body]
  `(let [fixture-fn# (join-fixtures (:clojure.test/each-fixtures (meta ~*ns*)))]
     (fixture-fn# (fn [] ~@body))))

; TODO: change order of expected/actual?
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
    (is (= pl-http/status-ok   (:status response)))
    (is (= pl-http/json-response-content-type (get-in response [:headers "Content-Type"])))
    (let [actual (when (:body response)
                   (-> (:body response)
                       (json/parse-string true)
                       (body-munge-fn)
                       (set)))]
      (is (= expected actual)
        (str response)))))

(defn assert-success!
  "Given a Ring response, verify that the status
  code is 200 OK.  If not, print the body and fail."
  [{:keys [status body] :as resp}]
  (when-not (= pl-http/status-ok status)
    (println "ERROR RESPONSE BODY:\n" body)
    (is (= pl-http/status-ok status))))

(defn get-request
  "Return a GET request against path, suitable as an argument to a ring
  app."
  ([path] (get-request path nil))
  ([path query] (get-request path query {}))
  ([path query params]
    (let [request (request :get path
                    (if query
                      (assoc params
                        "query" (if (string? query) query (json/generate-string query)))
                      params))
          headers (:headers request)]
      (assoc request :headers (assoc headers "Accept" c-t)))))


(defn paged-results*
  "Makes a ring request to `path` using the `app-fn` ring handler. Sets the necessary parameters
   for paged results.  Returns the ring response, with the body converted from the stream/JSON
   to clojure data structures."
  [{:keys [app-fn path query params limit total include-total offset] :as paged-test-params}]
  {:pre [(= #{} (difference
                 (keyset paged-test-params)
                 #{:app-fn :path :query :params :limit :total :include-total :offset}))]}
  (let [params  (merge params
                       {:limit limit
                        :offset offset})
        request (get-request path query
                             (if include-total
                               (assoc params :include-total true)
                               params))
        resp (app-fn request)
        body    (if (string? (:body resp))
                  (:body resp)
                  (slurp (:body resp)))]
    (assoc resp :body (json/parse-string body true))))

(defn paged-results
  "This function makes multiple calls to the ring handler `app-fn` to consume all of the
   results for `query`, a `limit` number of records at a time using the built in paging
   functions. See paged-results* for the code making the GET requests, this function
   drives the pages and the assertions of the result."
  [{:keys [app-fn path query params limit total include-total] :as paged-test-params}]
  {:pre [(= #{} (difference
                 (keyset paged-test-params)
                 #{:app-fn :path :query :params :limit :total :include-total}))]}
  (reduce
    (fn [coll n]
      (let [{:keys [status body headers] :as resp} (paged-results* (assoc paged-test-params :offset (* limit n)))]
        (assert-success! resp)
        (is (>= limit (count body)))
        (if include-total
          (do
            (is (contains? headers paging/count-header))
            (is (= total (parse-int (headers paging/count-header)))))
          (is (excludes? headers paging/count-header)))
        (concat coll body)))
    []
    (range (java.lang.Math/ceil (/ total (float limit))))))

(defn delete-on-exit
  "Will delete file `f` on shutdown of the JVM"
  [^java.io.File f]
  (doto f
    .deleteOnExit))

(def ^{:doc "Creates a temp file, deletes it on JVM shutdown"}
  temp-file (comp delete-on-exit fs/temp-file))

(def ^{:doc "Creates a temp directory, deletes the directory on JVM shutdown"}
  temp-dir (comp delete-on-exit fs/temp-dir))
