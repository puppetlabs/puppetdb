(ns com.puppetlabs.puppetdb.testutils.eventcounts
  (:require [com.puppetlabs.puppetdb.query.eventcounts :as event-counts]))

(defn event-counts-query-result
  ;; TODO docs
  ([query summarize-by]
    (event-counts-query-result query summarize-by nil))
  ([query summarize-by counts-filter]
    (->> (event-counts/query->sql query summarize-by counts-filter)
         (event-counts/query-event-counts)
         (set))))
