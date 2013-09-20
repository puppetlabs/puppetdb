(ns com.puppetlabs.puppetdb.testutils.events
  (:require [com.puppetlabs.puppetdb.query.events :as query]
            [com.puppetlabs.puppetdb.reports :as report])
  (:use [clj-time.coerce :only [to-timestamp]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn munge-v2-example-event-to-v1
  [example-event]
  (apply dissoc example-event report/v2-new-event-fields))

(defn munge-v2-example-events-to-v1
  [example-events]
  (mapv munge-v2-example-event-to-v1 example-events))

(defn munge-v1-example-event-to-v2
  [example-event]
  (reduce
    #(update-in %1 [%2] (constantly nil))
    example-event
    report/v2-new-event-fields))

(defn munge-v1-example-events-to-v2
  [example-events]
  (mapv munge-v1-example-event-to-v2 example-events))

(defn munge-example-event-for-storage
  "Helper function to munge our example reports into a format suitable for submission
  via the 'store report' command."
  [example-event]
  ;; Because we want to compare 'certname' in the output of event queries, the
  ;; example data includes it... but it is not a legal key for an event during
  ;; report submission.
  (dissoc example-event :certname :test-id :containing-class))

(defn expected-resource-event
  "Given a resource event from the example data, plus a report hash, coerce the
  event into the format that we expect to be returned from a real query."
  [example-resource-event report-hash configuration-version]
  (-> example-resource-event
    ;; the examples don't have the report-id or configuration-version,
    ;; but the results from the database do... so we need to munge those in.
    (assoc-in [:report] report-hash)
    (assoc-in [:configuration-version] configuration-version)
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)
    (dissoc :test-id)))

(defn expected-resource-events
  "Given a sequence of resource events from the example data, plus a report hash,
  coerce the events into the format that we expect to be returned from a real query."
  [example-resource-events report-hash configuration-version]
  (set (map #(expected-resource-event % report-hash configuration-version) example-resource-events)))

(defn resource-events-query-result
  "Utility function that executes a resource events query and returns a set of
  results for use in test comparisons."
  ([query] (resource-events-query-result query nil))
  ([query paging-options] (resource-events-query-result query paging-options nil))
  ([query paging-options query-options]
    (->> (query/query->sql query-options query)
         (query/query-resource-events paging-options)
         (:result)
         (set))))

(defn resource-events-limited-query-result
  "Utility function that executes a resource events query with a limit, and returns
  a set of results for use in test comparisons."
  ([limit query] (resource-events-limited-query-result limit query nil))
  ([limit query paging-options]
    (->> (query/query->sql nil query)
         (query/limited-query-resource-events limit paging-options)
         (:result)
         (set))))
