(ns puppetlabs.puppetdb.testutils.events
  (:require [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [clj-time.coerce :refer [to-timestamp to-string]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expected-resource-event
  "Given a resource event from the example data, plus a report hash, coerce the
  event into the format that we expect to be returned from a real query."
  [example-resource-event report]
  (-> example-resource-event
      ;; the examples don't have the report-id or configuration-version,
      ;; but the results from the database do... so we need to munge those in.
      (assoc :report (:hash report)
             :configuration_version (:configuration_version report)
             :run_start_time (to-timestamp (:start_time report))
             :run_end_time (to-timestamp (:end_time report))
             :report_receive_time (to-timestamp (:receive_time report)))
      ;; we need to convert the datetime fields from the examples to timestamp objects
      ;; in order to compare them.
      (update :timestamp to-timestamp)
      (assoc-when :environment (:environment report))))

(defn raw-expected-resource-events
  "Given a sequence of resource events from the example data, plus a report,
  coerce the events into the format that we expected to be returned from a real query.
  Unlike the more typical `expected-resource-events`, this does not put the events
  into a set, which makes this function useful for testing the order of results."
  [example-resource-events report]
  (map #(expected-resource-event % report) example-resource-events))

(defn timestamps->str
  "Walks events and stringifies all timestamps"
  [events]
  (walk/postwalk (fn [x]
                   (if (instance? java.sql.Timestamp x)
                     (to-string x)
                     x))
                 events))

(defn expected-resource-events
  "Given a sequence of resource events from the example data, plus a report,
  coerce the events into the format that we expect to be returned from a real query."
  [example-resource-events report]
  (-> example-resource-events
      (raw-expected-resource-events report)
      timestamps->str
      set))

(defn query-resource-events
  ([version query]
   (query-resource-events version query {}))
  ([version query query-options]
   (eng/stream-query-result :events
                            version
                            query
                            query-options
                            *db*
                            "")))
