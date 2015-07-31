(ns puppetlabs.puppetdb.testutils.log
  (:require
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.testutils :refer [temp-file]]
   [me.raynes.fs :as fs])
  (:import
   [ch.qos.logback.core Appender FileAppender]
   [ch.qos.logback.core.filter EvaluatorFilter]
   [ch.qos.logback.core.spi FilterReply LifeCycle]
   [ch.qos.logback.classic Level Logger]
   [ch.qos.logback.classic.boolex JaninoEventEvaluator]
   [ch.qos.logback.classic.encoder PatternLayoutEncoder]
   [org.slf4j LoggerFactory]))

(defn- find-logger [id]
  (.getLogger (LoggerFactory/getILoggerFactory)
              (if (class? id) id (str id))))

(defmacro with-log-level
  "Sets the (logback) log level for the logger specified by logger-id
  during the execution of body.  If logger-id is not a class, it is
  converted via str, and the level must be a clojure.tools.logging
  key, i.e. :info, :error, etc."
  [logger-id level & body]
  ;; Specify the root logger via org.slf4j.Logger/ROOT_LOGGER_NAME.
  ;; Assumes use of logback (i.e. logger supports Levels).
  `(let [logger# (#'puppetlabs.puppetdb.testutils.log/find-logger ~logger-id)
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

(defn- log-event-listener
  "Returns a log Appender that will call (listen event) for each log event."
  [listen]
  ;; No clue yet if we're supposed to start with a default name.
  (let [name (atom (str "log-listener-" (kitchensink/uuid)))]
    (reify
      Appender
      (doAppend [this event] (listen event))
      (getName [this] @name)
      (setName [this x] (reset! name x))
      LifeCycle
      (start [this] true)
      (stop [this] true))))

(defn- with-additional-log-appenders-fn [logger-id appenders body]
  (let [logger (find-logger logger-id)]
    (try
      (doseq [appender appenders]
        (.addAppender logger appender))
      (body)
      (finally
        (doseq [appender appenders]
          (.detachAppender logger appender))))))

(defmacro with-additional-log-appenders
  "Runs body with the appenders temporarily added to the logger
  specified by logger-id.  If logger-id is not a class, it is
  converted via str."
  [logger-id appenders & body]
  `(#'puppetlabs.puppetdb.testutils.log/with-additional-log-appenders-fn
     ~logger-id ~appenders (fn [] ~@body)))

(defn- with-log-appenders-fn [logger-id appenders body]
  (let [logger (find-logger logger-id)
        original-appenders (iterator-seq (.iteratorForAppenders logger))]
    (try
      (doseq [appender original-appenders]
        (.detachAppender logger appender))
      (with-additional-log-appenders-fn logger-id appenders body)
      (finally
        (doseq [appender original-appenders]
          (.addAppender logger appender))))))

(defmacro with-log-appenders
  "Runs body with the current appenders of the logger specified by
  logger-id replaced by the specified appenders.  If logger-id is not
  a class, it is converted via str."
  [logger-id appenders & body]
  `(#'puppetlabs.puppetdb.testutils.log/with-log-appenders-fn
     ~logger-id ~appenders (fn [] ~@body)))

(defmacro with-log-event-listener
  "Calls (listen event) for each logger-id event produced during the
  execution of the body.  If logger-id is not a class, it is converted
  via str."
  [logger-id listen & body]
  ;; Specify the root logger via org.slf4j.Logger/ROOT_LOGGER_NAME.
  `(with-additional-log-appenders ~logger-id
     [(doto (#'puppetlabs.puppetdb.testutils.log/log-event-listener ~listen)
        .start)]
     (do ~@body)))

(defmacro with-logging-to-atom
  "Conjoins all logger-id events produced during the execution of the
  body to the destination atom, which must contain a collection.  If
  logger-id is not a class, it is converted via str."
  [logger-id destination & body]
  ;; Specify the root logger via org.slf4j.Logger/ROOT_LOGGER_NAME.
  `(with-log-event-listener
     ~logger-id
     (fn [event#] (swap! ~destination conj event#))
     ~@body))

(def ^:private amqp-error-filter
  (str "String m = throwable.getMessage(); "
       "return javax.jms.JMSException.class.isInstance(throwable) && "
       "  m.contains(\"peer\") && "
       "  m.contains(\"stopped\"); "))

(defn- suppressing-appender
  [log-path]
  (let [pattern "%-4relative [%thread] %-5level %logger{35} - %msg%n"
        context (LoggerFactory/getILoggerFactory)]
    (doto (FileAppender.)
      (.setFile log-path)
      (.setAppend true)
      (.setEncoder (doto (PatternLayoutEncoder.)
                     (.setPattern pattern)
                     (.setContext context)
                     .start))
      ;; Haven't checked the filter yet.
      (.addFilter (doto (EvaluatorFilter.)
                    (.setContext context)
                    (.setEvaluator (doto (JaninoEventEvaluator.)
                                     (.setContext context)
                                     (.setExpression amqp-error-filter)
                                     .start))
                    (.setOnMatch FilterReply/DENY)
                    (.setOnMismatch FilterReply/NEUTRAL)
                    .start))
      (.setContext context)
      .start)))

(defn- suppressing-log-unless-error-fn [f]
  (let [problem (atom false)
        detector (doto (#'puppetlabs.puppetdb.testutils.log/log-event-listener
                        (fn [event]
                          (let [level (.getLevel event)]
                            (when (.isGreaterOrEqual level Level/ERROR)
                              (reset! problem true)))))
                   .start)
        log-path (fs/absolute-path (temp-file "pdb-suppressed" ".log"))
        appender (suppressing-appender log-path)]
    (try
      (with-log-appenders org.slf4j.Logger/ROOT_LOGGER_NAME
        [appender detector]
        (f))
      (finally
        (.stop appender)
        (when @problem
          (binding [*out* *err*]
            (print (slurp log-path))
            (println "From error log: " log-path)))
        (when-not @problem (fs/delete log-path))))))

(defmacro suppressing-log-unless-error
  "Executes the body with all logging suppressed.  If a
  \"significant\" error is logged, dumps the full log to *err* along
  with its path.  Assumes that the logging level is already set as
  desired."
  [& body]
  ;; Assumes the log level is as desired
  `(#'puppetlabs.puppetdb.testutils.log/suppressing-log-unless-error-fn
    (fn [] ~@body)))
