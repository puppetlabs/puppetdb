(ns com.puppetlabs.puppetdb.testutils.eventcounts
  (:require [com.puppetlabs.puppetdb.query.eventcounts :as event-counts]))

(defn event-counts-query-result
  ;; TODO docs
  [query summarize-by]
  (->> (event-counts/query->sql query summarize-by)
       (event-counts/query-event-counts)
       (set)))
