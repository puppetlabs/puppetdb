(ns com.puppetlabs.puppetdb.testutils
  (:import (org.apache.activemq.broker BrokerService))
  (:require [com.puppetlabs.mq :as mq]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging.impl :as impl]
            [fs.core :as fs]))

(defn test-db
  "Return a map of connection attrs for an in-memory database"
  []
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (str "mem:"
                     (java.util.UUID/randomUUID)
                     ";shutdown=true;hsqldb.tx=mvcc;sql.syntax_pgs=true")})

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

(defn atom-logger [output-atom]
  "A logger factory that logs output to the supplied atom"
  (reify impl/LoggerFactory
    (name [_] "test factory")
    (get-logger [_ log-ns]
      (reify impl/Logger
        (enabled? [_ level] true)
        (write! [_ lvl ex msg]
          (swap! output-atom conj [(str log-ns) lvl ex msg]))))))
