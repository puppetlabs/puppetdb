(ns com.puppetlabs.puppetdb.testutils.event
  (:require [com.puppetlabs.puppetdb.query.event :as query])
  (:use [clj-time.coerce :only [to-timestamp]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expected-resource-event
  [example-resource-event report-hash]
  (-> example-resource-event
    ;; the examples don't have the report-id, but the results from the
    ;; database do... so we need to munge that in.
    (assoc-in [:report] report-hash)
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)))

(defn expected-resource-events
  [example-resource-events report-hash]
  (set (map #(expected-resource-event % report-hash) example-resource-events)))

(defn resource-events-query-result
  [query]
  (set (->> (query/resource-event-query->sql query)
         (query/query-resource-events))))
