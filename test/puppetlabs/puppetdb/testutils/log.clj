(ns puppetlabs.puppetdb.testutils.log
  (:import
   [ch.qos.logback.classic Level]))

;;The below functions are useful with
;;puppetlabs.trapperkeeper.testutils.logging/with-log-suppressed-unless-notable

(defn notable-pdb-event?
  [event]
  (and (.isGreaterOrEqual (.getLevel event) Level/ERROR)
       ;; FIXME don't filter out these errors, configure the tests properly
       (not (re-find #"^The read-database user is not configured properly"
                     (.getFormattedMessage event)))))
