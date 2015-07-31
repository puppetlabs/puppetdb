(ns puppetlabs.puppetdb.testutils.log
  (:require [puppetlabs.kitchensink.core :as kitchensink])
  (:import [ch.qos.logback.core spi.LifeCycle Appender]
           [ch.qos.logback.classic Level Logger]
           [org.slf4j LoggerFactory]))

(defmacro with-log-level
  "Sets the (logback) log level for the logger specified by logger-id
  during the execution of body.  If logger-id is not a class, it is
  converted via str, and the level must be a clojure.tools.logging
  key, i.e. :info, :error, etc."
  [logger-id level & body]
  ;; Assumes use of logback (i.e. logger supports Levels).
  `(let [logger-id# ~logger-id
         logger-id# (if (class? logger-id#) logger-id# (str logger-id#))
         logger# (.getLogger (LoggerFactory/getILoggerFactory) logger-id#)
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

(defn create-log-appender-to-atom
  [destination-atom]
  ;; No clue yet if we're supposed to start with a default name.
  (let [name (atom (str "log-appender-to-atom-" (kitchensink/uuid)))]
    (reify
      Appender
      (doAppend [this event] (swap! destination-atom conj event))
      (getName [this] @name)
      (setName [this x] (reset! name x))
      LifeCycle
      (start [this] true)
      (stop [this] true))))

(defmacro with-logging-to-atom
  "Conjoins all logger-id events produced during the execution of the
  body to the destination atom, which must contain a collection.  If
  logger-id is not a class, it is converted via str."
  [logger-id destination & body]
  ;; Specify the root logger via org.slf4j.Logger/ROOT_LOGGER_NAME.
  `(let [logger-id# ~logger-id
         logger-id# (if (class? logger-id#) logger-id# (str logger-id#))
         logger# (.getLogger (LoggerFactory/getILoggerFactory) logger-id#)
         appender# (doto (create-log-appender-to-atom ~destination) .start)]
     (.addAppender logger# appender#)
     (try
       (do ~@body)
       (finally (.detachAppender logger# appender#)))))
