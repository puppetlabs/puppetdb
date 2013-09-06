(ns com.puppetlabs.puppetdb.testutils.eventcounts
  (:require [com.puppetlabs.puppetdb.query.eventcounts :as event-counts]))

(defn event-counts-query-result
  ;; TODO docs
  [query summarize-by counts-filter count-by]
    (->> (event-counts/query->sql query summarize-by counts-filter (or count-by "resource"))
         (event-counts/query-event-counts)
         (set)))
