(ns com.puppetlabs.puppetdb.testutils
  (:import (org.apache.activemq.broker BrokerService))
  (:require [com.puppetlabs.mq :as mq]
            [com.puppetlabs.http :as pl-http]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [fs.core :as fs])
  (:use     [com.puppetlabs.puppetdb.scf.storage :only [sql-current-connection-table-names]]
            [com.puppetlabs.utils :only [swap-and-return-old-val!]]
            [clojure.test]))

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
  `(let [dir#                   (fs/absolute-path (fs/temp-dir))
         broker-name#           ~name
         conn-str#              (str "vm://" ~name)
         ^BrokerService broker# (mq/build-embedded-broker broker-name# dir#)]

     (.setUseJmx broker# false)
     (.setPersistent broker# false)
     (mq/start-broker! broker#)

     (try
       (with-open [~conn-var (mq/connect! conn-str#)]
         ~@body)
       (finally
         (mq/stop-broker! broker#)
         (fs/delete-dir dir#)))))

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
to the result of the form supplied to this method."
  ([response expected]
    (response-equal? response expected identity))
  ([response expected body-munge-fn]
    (is (= pl-http/status-ok   (:status response)))
    (is (= "application/json" (get-in response [:headers "Content-Type"])))
    (let [actual  (if (:body response)
      (set (body-munge-fn (json/parse-string (:body response) true)))
      nil)]
      (is (= expected actual)
        (str response)))))
