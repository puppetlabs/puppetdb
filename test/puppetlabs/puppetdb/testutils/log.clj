(ns puppetlabs.puppetdb.testutils.log
  (:import
   [ch.qos.logback.classic Level]))

;;The below functions are useful with
;;puppetlabs.trapperkeeper.testutils.logging/with-log-suppressed-unless-notable

(defn notable-pdb-event? [event]
  (.isGreaterOrEqual (.getLevel event) Level/ERROR))
