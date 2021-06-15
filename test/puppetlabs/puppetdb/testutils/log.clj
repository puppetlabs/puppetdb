(ns puppetlabs.puppetdb.testutils.log
  (:require
   [clojure.java.io :as io])
  (:import
   [ch.qos.logback.classic Level]
   [ch.qos.logback.classic.spi ILoggingEvent]))

;;The below functions are useful with
;;puppetlabs.trapperkeeper.testutils.logging/with-log-suppressed-unless-notable

(defn notable-pdb-event?
  [event]
  (and (.isGreaterOrEqual (.getLevel event) Level/ERROR)
       (not (or
              ;; FIXME don't filter out these errors, configure the tests properly
              (re-find #"^The read-database user is not configured properly"
                       (.getFormattedMessage event))
              (and (-> (.getFormattedMessage event)
                       (.contains "queue connection error"))
                   (when-let [cause (.getThrowableProxy event)]
                     (re-find #"peer.*vm://localhost.*stopped" (.getMessage cause))))))))

(defn critical-errors
  [^ILoggingEvent event]
  (= Level/ERROR
     (.getLevel event)))

(defn starting-with
  "Filters log events that have a message starting with `string`"
  [string]
  (fn [^ILoggingEvent event]
    (-> event
        .getMessage
        (.startsWith string))))
