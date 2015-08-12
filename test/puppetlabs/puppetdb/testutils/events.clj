(ns puppetlabs.puppetdb.testutils.events
  (:require [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [clj-time.coerce :refer [to-timestamp to-string]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn munge-example-event-for-storage
  "Helper function to munge our example reports into a format suitable for submission
  via the 'store report' command."
  [example-event]
  ;; Because we want to compare 'certname' in the output of event queries, the
  ;; example data includes it... but it is not a legal key for an event during
  ;; report submission.
  (dissoc example-event :certname :test_id :containing_class :environment))

(defn environment [resource-event report version]
  (if (= :v4 version)
    (assoc-when resource-event :environment (:environment report))
    (dissoc resource-event :environment)))

(defn expected-resource-event
  "Given a resource event from the example data, plus a report hash, coerce the
  event into the format that we expect to be returned from a real query."
  [version example-resource-event report]
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
      (environment report version)
      (dissoc :test_id)))

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

(defn query-resource-events
  ([version query]
   (query-resource-events version query {}))
  ([version query query-options]
   (eng/stream-query-result :events
                            version
                            query
                            query-options
                            fixt/*db*
                            "")))
