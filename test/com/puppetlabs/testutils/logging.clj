(ns com.puppetlabs.testutils.logging
  (:require [clojure.tools.logging.impl :as impl]
            [clojure.tools.logging :only [*logger-factory*]])
  (:import [org.apache.log4j Logger AppenderSkeleton Level]))

(defn- log-entry->map
  [log-entry]
  {:namespace (get log-entry 0)
   :level     (get log-entry 1)
   :exception (get log-entry 2)
   :message   (get log-entry 3)})

(defn logs-matching
  "Given a regular expression pattern and a sequence of log messages (in the format
  used by `clojure.tools.logging`, return only the logs whose message matches the
  specified regular expression pattern.  (Intended to be used alongside
  `with-log-output` for tests that are validating log output.)  The result is
  a sequence of maps, each of which contains the following keys:
  `:namespace`, `:level`, `:exception`, and `:message`."
  [pattern logs]
  {:pre  [(instance? java.util.regex.Pattern pattern)
          (coll? logs)]}
  ;; the logs are formatted as sequences, where the string at index 3 contains
  ;; the actual log message.
  (let [matches (filter #(re-find pattern (get % 3)) logs)]
    (map log-entry->map matches)))


(defn atom-logger [output-atom]
  "A logger factory that logs output to the supplied atom"
  (reify impl/LoggerFactory
    (name [_] "test factory")
    (get-logger [_ log-ns]
      (reify impl/Logger
        (enabled? [_ level] true)
        (write! [_ lvl ex msg]
          (swap! output-atom conj [(str log-ns) lvl ex msg]))))))

(defn atom-appender
  "Creates a log4j appender that writes log messages to the supplied atom"
  [output-atom]
  (proxy [AppenderSkeleton] []
    (append [logging-event]
      (let [throwable-info  (.getThrowableInformation logging-event)
            ex              (if throwable-info (.getThrowable throwable-info))]
        (swap! output-atom conj
          [(.getLoggerName logging-event)
           (.getLevel logging-event)
           ex
           (str (.getMessage logging-event))])))
    (close [])))

(defmacro with-log-output
  "Sets up a temporary logger to capture all log output to a sequence, and
  evaluates `body` in this logging context.

  `log-output-var` - Inside of `body`, the variable named `log-output-var`
  is a clojure atom containing the sequence of log messages that have been logged
  so far.  You can access the individual log messages by dereferencing this
  variable (with either `deref` or `@`).

  Example:

      (with-log-output logs
        (log/info \"Hello There\")
        (is (= 1 (count (logs-matching #\"Hello There\" @logs)))))"
  [log-output-var & body]
  `(let [~log-output-var  (atom [])
         root-logger#     (Logger/getRootLogger)
         orig-appenders#  (vec (enumeration-seq (.getAllAppenders root-logger#)))
         orig-levels#     (into {} (map #(vector % (.getThreshold %)) orig-appenders#))
         temp-appender#   (atom-appender ~log-output-var)]
     (.setName temp-appender# "testutils-temp-log-appender")
     (doseq [orig-appender# orig-appenders#]
       (.setThreshold orig-appender# Level/OFF))
     (.addAppender root-logger# temp-appender#)
     (binding [clojure.tools.logging/*logger-factory* (atom-logger ~log-output-var)]
       ~@body)
     (.removeAppender root-logger# temp-appender#)
     (doseq [orig-appender# orig-appenders#]
       (.setThreshold orig-appender# (orig-levels# orig-appender#)))))
