(ns puppetlabs.puppetdb.testutils.log
  (:import
   [ch.qos.logback.classic Level]))

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
