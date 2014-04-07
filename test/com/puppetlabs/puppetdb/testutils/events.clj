(ns com.puppetlabs.puppetdb.testutils.events
  (:require [com.puppetlabs.puppetdb.query.events :as query]
            [com.puppetlabs.puppetdb.reports :as report]
            [com.puppetlabs.puppetdb.http :refer [remove-environment]]
            [clojure.walk :as walk])
  (:use [clj-time.coerce :only [to-timestamp to-string]]))

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

(defn add-environment [resource-event report version]
  (if (= :v4 version)
    (assoc resource-event :environment (:environment report))
    resource-event))

(defn expected-resource-event
  "Given a resource event from the example data, plus a report hash, coerce the
  event into the format that we expect to be returned from a real query."
  [version example-resource-event report]
  (-> example-resource-event
    ;; the examples don't have the report-id or configuration-version,
    ;; but the results from the database do... so we need to munge those in.
    (assoc-in [:report] (:hash report))
    (assoc-in [:configuration-version] (:configuration-version report))
    (assoc-in [:run-start-time] (to-timestamp (:start-time report)))
    (assoc-in [:run-end-time] (to-timestamp (:end-time report)))
    (assoc-in [:report-receive-time] (to-timestamp (:receive-time report)))
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)
    (add-environment report version)
    (dissoc :test-id)))

(defn raw-expected-resource-events
  "Given a sequence of resource events from the example data, plus a report,
  coerce the events into the format that we expected to be returned from a real query.
  Unlike the more typical `expected-resource-events`, this does not put the events
  into a set, which makes this function useful for testing the order of results."
  [version example-resource-events report]
  (map #(expected-resource-event version % report) example-resource-events))

(defn timestamps->str
  "Walks events and stringifies all timestamps"
  [events]
  (walk/postwalk (fn [x]
                   (if (instance? java.sql.Timestamp x)
                     (to-string x)
                     x))
                 events))

(defn http-expected-resource-events
  "Returns an HTTPish version of resource events"
  [version example-resource-events report]
  (-> (raw-expected-resource-events version example-resource-events report)
      timestamps->str
      set))

(defn expected-resource-events
  "Given a sequence of resource events from the example data, plus a report,
  coerce the events into the format that we expect to be returned from a real query."
  [version example-resource-events report]
  (set (raw-expected-resource-events version example-resource-events report)))

(defn resource-events-query-result
  "Utility function that executes a resource events query and returns a set of
  results for use in test comparisons."
  ([version query]
    (resource-events-query-result version query nil))
  ([version query paging-options]
    (resource-events-query-result version query paging-options nil))
  ([version query paging-options query-options]
    (->> (query/query->sql version query-options query)
         (query/query-resource-events version paging-options)
         (:result)
         (set))))

(defn resource-events-limited-query-result
  "Utility function that executes a resource events query with a limit, and returns
  a set of results for use in test comparisons."
  ([version limit query]
    (resource-events-limited-query-result version limit query nil))
  ([version limit query paging-options]
    (->> (query/query->sql version nil query)
         (query/limited-query-resource-events version limit paging-options)
         (:result)
         (set))))

(defn raw-resource-events-query-result
  "Utility function that executes a resource events query with paging options and
  simply returns the map with the query results and any metadata for use in test
  comparisons. This does not do anything to the results from the query."
  ([version query paging-options]
    (raw-resource-events-query-result version query paging-options nil))
  ([version query paging-options query-options]
    (->> (query/query->sql version query-options query)
         (query/query-resource-events version paging-options))))
