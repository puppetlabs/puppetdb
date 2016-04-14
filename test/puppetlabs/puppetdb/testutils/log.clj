(ns puppetlabs.puppetdb.testutils.log
  (:require
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.testutils :refer [temp-file]]
   [me.raynes.fs :as fs])
  (:import
   [ch.qos.logback.core Appender FileAppender]
   [ch.qos.logback.core.spi LifeCycle]
   [ch.qos.logback.classic Level Logger]
   [ch.qos.logback.classic.encoder PatternLayoutEncoder]
   [org.slf4j LoggerFactory]))

(defmacro with-started
  "Ensures that if a given name's init form executes without throwing
  an exception, (.stop name) will be called before returning from
  with-started.  It is the responsibility of the init form to make
  sure the object has been started.  This macro behaves like
  with-open, but with respect to .stop instead of .close."
  [[name init & bindings] & body]
  (if-let [s (seq bindings)]
    `(let [~name ~init]
       (try
         (with-started ~bindings ~@body)
         (finally (.stop ~name))))
    `(let [~name ~init]
       (try
         ~@body
         (finally (.stop ~name))))))

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
     (try
       (.setLevel logger# (case ~level
                            :trace Level/TRACE
                            :debug Level/DEBUG
                            :info Level/INFO
                            :warn Level/WARN
                            :error Level/ERROR
                            :fatal Level/ERROR))
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

(defn- call-with-additional-log-appenders [logger-id appenders body]
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
  `(#'puppetlabs.puppetdb.testutils.log/call-with-additional-log-appenders
     ~logger-id ~appenders (fn [] ~@body)))

(defn- call-with-log-appenders [logger-id appenders body]
  (let [logger (find-logger logger-id)
        original-appenders (iterator-seq (.iteratorForAppenders logger))]
    (try
      (doseq [appender original-appenders]
        (.detachAppender logger appender))
      (call-with-additional-log-appenders logger-id appenders body)
      (finally
        (doseq [appender original-appenders]
          (.addAppender logger appender))))))

(defmacro with-log-appenders
  "Runs body with the current appenders of the logger specified by
  logger-id replaced by the specified appenders.  If logger-id is not
  a class, it is converted via str."
  [logger-id appenders & body]
  `(#'puppetlabs.puppetdb.testutils.log/call-with-log-appenders
     ~logger-id ~appenders (fn [] ~@body)))

(defmacro with-log-event-listener
  "Calls (listen event) for each logger-id event produced during the
  execution of the body.  If logger-id is not a class, it is converted
  via str."
  [logger-id listen & body]
  ;; Specify the root logger via org.slf4j.Logger/ROOT_LOGGER_NAME.
  `(with-started [listener#
                  (doto (#'puppetlabs.puppetdb.testutils.log/log-event-listener
                         ~listen)
                    .start)]
     (with-additional-log-appenders ~logger-id [listener#]
       (do ~@body))))

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

(defn- suppressing-file-appender
  [log-path]
  (let [pattern "%-4relative [%thread] %-5level %logger{35} - %msg%n"
        context (LoggerFactory/getILoggerFactory)]
    (doto (FileAppender.)
      (.setFile log-path)
      (.setAppend true)
      (.setEncoder (doto (PatternLayoutEncoder.)
                     (.setPattern pattern)
                     (.setContext context)
                     (.start)))
      (.setContext context)
      (.start))))

(def ^:private annoying-peer-error-rx #"peer.*vm://localhost.*stopped")

(defn notable-pdb-event? [event]
  (and (.isGreaterOrEqual (.getLevel event) Level/ERROR)
       (not (and (-> (.getFormattedMessage event)
                     (.contains "queue connection error"))
                 (when-let [cause (.getThrowableProxy event)]
                   (re-find annoying-peer-error-rx (.getMessage cause)))))))

(defn- call-with-log-suppressed-unless-notable [notable-event? f]
  (let [problem (atom false)
        log-path (kitchensink/absolute-path (temp-file "pdb-suppressed" ".log"))]
    (try
      (with-started
        [appender (suppressing-file-appender log-path)
         detector (doto (#'puppetlabs.puppetdb.testutils.log/log-event-listener
                         (fn [event]
                           (when (notable-event? event)
                             (reset! problem true))))
                    .start)]
        (with-log-appenders org.slf4j.Logger/ROOT_LOGGER_NAME
          [appender detector]
          (f)))
      (finally
        (when @problem
          (binding [*out* *err*]
            (print (slurp log-path))
            (println "From error log:" log-path)))
        (when-not @problem (fs/delete log-path))))))

(defmacro with-log-suppressed-unless-notable
  "Executes the body with all logging suppressed.  If a notable error
  is logged, dumps the full log to *err* along with its path.  Assumes
  that the logging level is already set as desired.  This may not work
  correctly if the system logback config is altered during the
  execution of the body."
  [notable-event? & body]
  `(#'puppetlabs.puppetdb.testutils.log/call-with-log-suppressed-unless-notable
    ~notable-event?
    (fn [] ~@body)))
