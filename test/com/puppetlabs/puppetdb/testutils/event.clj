(ns com.puppetlabs.puppetdb.testutils.event
  (:require [com.puppetlabs.puppetdb.query.event :as query])
  (:use [clj-time.coerce :only [to-timestamp]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expected-resource-event
  "Given a resource event from the example data, plus a report hash, coerce the
  event into the format that we expect to be returned from a real query."
  [example-resource-event report-hash]
  (-> example-resource-event
    ;; the examples don't have the report-id, but the results from the
    ;; database do... so we need to munge that in.
    (assoc-in [:report] report-hash)
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)))

(defn expected-resource-events
  "Given a sequence of resource events from the example data, plus a report hash,
  coerce the events into the format that we expect to be returned from a real query."
  [example-resource-events report-hash]
  (set (map #(expected-resource-event % report-hash) example-resource-events)))

(defn resource-events-query-result
  "Utility function that executes a resource events query and returns a set of
  results for use in test comparisons."
  [query]
  (set (->> (query/query->sql query)
            (query/query-resource-events))))


(defn resource-events-limited-query-result
  "Utility function that executes a resource events query with a limit, and returns
  a set of results for use in test comparisons."
  [limit query]
  (set (->> (query/query->sql query)
            ((partial query/limited-query-resource-events limit)))))
