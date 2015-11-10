(ns puppetlabs.puppetdb.testutils.reports
  (:require [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [clj-time.coerce :as time-coerce]
            [clojure.walk :refer [keywordize-keys]]
            [flatland.ordered.map :as omap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
                            *db*
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
   (let [example-report (reports/report-query->wire-v6 example-report)
         report-hash (shash/report-identity-hash
                      (scf-store/normalize-report example-report))]
     (scf-store/maybe-activate-node! (:certname example-report) timestamp)
     (scf-store/add-report!* example-report timestamp update-latest-report?)
     (report-for-hash :v4 report-hash))))

(defn munge-resource-events-for-comparison
  [resource-events]
  (set
   (map (fn [resource-event]
          (-> resource-event
              (update :timestamp time-coerce/to-string)
              (dissoc :environment :containing_class :certname)))
        resource-events)))

(defn munge-children
  "Strips out expanded data from the wire format if the database is HSQLDB"
  [report]
  (-> report
      (update :resource_events munge-resource-events-for-comparison)
      (update :metrics set)
      (update :logs set)))

(defn normalize-time
  "Normalize start_time end_time, by coercing it, it forces the timezone to
  become consistent during comparison."
  [report]
  (kitchensink/mapvals
   time-coerce/to-string
   [:start_time :end_time :producer_timestamp]
   report))

(defn munge-report-for-comparison
  [report]
  (-> report
      keywordize-keys
      normalize-time
      munge-children))

(defn munge-reports-for-comparison
  "Convert actual results for reports queries to wire format ready for comparison."
  [reports]
  (set
   (map munge-report-for-comparison
        (reports/reports-query->wire-v5 reports))))

(defn enumerated-resource-events-map
  [resource-events]
  (->> resource-events
       kitchensink/enumerate
       (into (omap/ordered-map))))

(defn munge-resource-events [resource-events]
  (->> resource-events
       (map #(update % :timestamp time-coerce/to-string))
       (sort-by #(mapv % [:timestamp :resource_type :resource_title :property]))))

(defn munge-report
  [report]
  (-> report
      keywordize-keys
      (update-in [:resource_events :data] munge-resource-events)
      normalize-time))

(defn munge-reports [reports]
  (map munge-report reports))

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
