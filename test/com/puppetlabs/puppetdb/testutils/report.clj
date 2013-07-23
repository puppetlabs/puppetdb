(ns com.puppetlabs.puppetdb.testutils.report
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.query.report :as query]
            [clj-time.coerce :as time-coerce])
  (:use [com.puppetlabs.puppetdb.testutils.event :only [munge-example-event-for-storage]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn munge-example-report-for-storage
  [example-report]
  (update-in example-report [:resource-events]
    #(mapv munge-example-event-for-storage %)))

(defn munge-events-for-comparison
  "Munges event objects in a way that is suitable for equality comparisons in tests"
  [events]
  {:pre  [(vector? events)]
   :post [(set? %)]}
  (set (map
      #(-> %
        (update-in ["timestamp"] time-coerce/to-string)
        (dissoc "report")
        (dissoc "certname"))
      events)))

(defn munge-report-for-comparison
  "Given a report object (represented as a map, either having come out of a
  puppetdb database query or parsed from the JSON wire format), munge it and
  return a version that will be suitable for equality comparison in tests.  This
  mostly entails mapping certain fields that would be represented as JSON arrays--
  but whose ordering is not actually relevant for equality testing--to sets (which
  JSON doesn't have a data type for)."
  [report]
  {:pre  [(map? report)]
   :post [(map? %)
          (set? (% "resource-events"))]}
  (-> report
    (clojure.walk/stringify-keys)
    (update-in ["start-time"] time-coerce/to-string)
    (update-in ["end-time"] time-coerce/to-string)
    (update-in ["resource-events"] munge-events-for-comparison)
    (dissoc "hash")
    (dissoc "receive-time")))

(defn store-example-report!
  [example-report timestamp]
  (let [example-report  (munge-example-report-for-storage example-report)
        report-hash     (scf-store/report-identity-string example-report)]
    (report/validate! 1 example-report)
    (scf-store/maybe-activate-node! (:certname example-report) timestamp)
    (scf-store/add-report! example-report timestamp)
    report-hash))

(defn expected-report
  [example-report]
  (utils/mapvals
    ;; we need to map the datetime fields to timestamp objects for comparison
    time-coerce/to-timestamp
    [:start-time :end-time]
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc example-report :resource-events)))

(defn expected-reports
  [example-reports]
  (map expected-report example-reports))

(defn reports-query-result
  [query]
  (vec (->> (query/report-query->sql query)
         (query/query-reports)
         ;; the example reports don't have a receive time (because this is
         ;; calculated by the server), so we remove this field from the response
         ;; for test comparison
         (map #(dissoc % :receive-time)))))

(defn get-events-map
  [example-report]
  (into {}
    (for [ev (:resource-events example-report)]
      [(:test-id ev) ev])))
