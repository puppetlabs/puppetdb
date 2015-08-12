(ns puppetlabs.puppetdb.testutils.reports
  (:require [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clj-time.coerce :as time-coerce]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.testutils.events :refer [munge-example-event-for-storage]]
            [flatland.ordered.map :as omap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn munge-example-report-for-storage
  [example-report]
  (update example-report :resource_events (partial mapv munge-example-event-for-storage)))

(defn munge-events-for-comparison
  "Munges event objects in a way that is suitable for equality comparisons in tests"
  [events]
  {:pre  [(vector? events)]
   :post [(set? %)]}
  (->> events
       (map #(-> %
                 (update "timestamp" time-coerce/to-string)
                 (dissoc "report" "certname" "configuration_version")))
       set))

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
          (set? (% "resource_events"))]}
  (-> report
      clojure.walk/stringify-keys
      (update "start_time" time-coerce/to-string)
      (update "end_time" time-coerce/to-string)
      (update "producer_timestamp" time-coerce/to-string)
      (update "resource_events" munge-events-for-comparison)
      (dissoc "hash" "receive_time")))

(defn report-for-hash
  "Convenience function; given a report hash, return the corresponding report object
  (without events)."
  [version hash]
  {:pre  [(string? hash)]
   :post [(or (nil? %)
              (map? %))]}
  (first
   (eng/stream-query-result :reports
                            version
                            ["=" "hash" hash]
                            {}
                            fixt/*db*
                            "")))

(defn store-example-report!
  "Store an example report (from examples/report.clj) for use in tests.  Params:

  - `validate-fn`: no-arg function called to validate the catalog
  - `example-report`: the report (as a map)
  - `timestamp`: the `received-time` for the report
  - `update-latest-report?` (optional): if `false`, then the `latest_reports` table
  will not be updated to reflect the new report.  Defaults to `true`.  This only
  exists to allow testing of the schema migration code; you should almost never pass
  a value for this."
  ([example-report timestamp]
   (store-example-report! example-report timestamp true))
  ([example-report timestamp update-latest-report?]
   (let [example-report (munge-example-report-for-storage example-report)
         report-hash (shash/report-identity-hash
                      (scf-store/normalize-report example-report))]
     (scf-store/maybe-activate-node! (:certname example-report) timestamp)
     (scf-store/add-report!* example-report timestamp update-latest-report?)
     (report-for-hash :v4 report-hash))))

(defn expected-report
  [example-report]
  (kitchensink/mapvals
   ;; we need to map the datetime fields to timestamp objects for comparison
   time-coerce/to-timestamp
   [:start_time :end_time :producer_timestamp]
   ;; the response won't include individual events, so we need to pluck those
   ;; out of the example report object before comparison
   example-report))

(defn munge-resource-events
  [xs]
  (->> xs
       (map #(-> %
                 (update :timestamp time-coerce/to-string)
                 (dissoc :environment :test_id :containing_class :certname)))
       set))

(defn expected-reports
  [example-reports]
  (map expected-report example-reports))

(defn reports-query-result
  [version query & [paging-options]]
  ;; the example reports don't have a receive time (because this is
  ;; calculated by the server), so we remove this field from the response
  ;; for test comparison
  (->> (eng/stream-query-result :reports
                                version
                                query
                                paging-options
                                fixt/*db*
                                "")
       (map #(dissoc % :receive_time))))

(defn get-events-map
  [example-report]
  (into (omap/ordered-map)
        (for [ev (:resource_events example-report)]
          [(:test_id ev) ev])))

(defn munge-report
  "Munges a catalog of list of reports for comparision.
  Returns a list of reports."
  [report-or-reports]
  (->> report-or-reports
       utils/vector-maybe
       (map (comp munge-report-for-comparison
                  munge-example-report-for-storage))))

(defn is-latest-report?
  "Given a node and a report hash, return `true` if the report is the most recent one for the node,
  and `false` otherwise."
  [node report-hash]
  {:pre  [(string? node)
          (string? report-hash)]
   :post [(kitchensink/boolean? %)]}
  (= 1 (count (jdbc/query-to-vec
                [(format "SELECT %s as latest_report_hash
                          FROM certnames
                          INNER JOIN reports ON reports.id = certnames.latest_report_id
                          WHERE certnames.certname = ? AND %s = ?"
                         (sutils/sql-hash-as-str "reports.hash")
                         (sutils/sql-hash-as-str "reports.hash"))
                node report-hash]))))
