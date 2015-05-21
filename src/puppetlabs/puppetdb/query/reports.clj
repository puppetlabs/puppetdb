(ns puppetlabs.puppetdb.query.reports
  (:require [clojure.set :as set]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s])
  (:import  [org.postgresql.util PGobject]))

;; MUNGE

(pls/defn-validated rtj->event :- reports/resource-event-query-schema
  "Convert row_to_json format to real data."
  [event :- {s/Keyword s/Any}]
  (-> event
      (update :old_value json/parse-string)
      (update :new_value json/parse-string)))

(pls/defn-validated row->report
  "Convert a report query row into a final report format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:resource_events] utils/child->expansion :reports :events base-url)
        (utils/update-when [:resource_events :data] (partial map rtj->event))
        (utils/update-when [:metrics] utils/child->expansion :reports :metrics base-url)
        (utils/update-when [:logs] utils/child->expansion :reports :logs base-url))))

(pls/defn-validated munge-result-rows
  "Reassemble report rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (str url-prefix "/" (name version))]
    (fn [rows]
      (map (row->report base-url) rows))))

;; QUERY

(def report-columns
  [:hash
   :puppet_version
   :receive_time
   :report_format
   :start_time
   :end_time
   :producer_timestamp
   :noop
   :transaction_uuid
   :status
   :environment
   :configuration_version
   :metrics
   :logs
   :certname])

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  ([version query]
   (query->sql version query {}))
  ([version query paging-options]
   {:pre  [((some-fn nil? sequential?) query)]
    :post [(map? %)
           (jdbc/valid-jdbc-query? (:results-query %))
           (or (not (:count? paging-options))
               (jdbc/valid-jdbc-query? (:count-query %)))]}
   (paging/validate-order-by! report-columns paging-options)
   (qe/compile-user-query->sql
    qe/reports-query query paging-options)))

;; QUERY + MUNGE

(defn query-reports
  "Queries reports and unstreams, used mainly for testing.

  This wraps the existing streaming query code but returns results
  and count (if supplied)."
  [version url-prefix query-sql]
  {:pre [(map? query-sql)]}
  (let [{:keys [results-query count-query]} query-sql
        munge-fn (munge-result-rows version url-prefix)]
    (cond-> {:result (->> (jdbc/with-query-results-cursor results-query)
                          munge-fn
                          (into []))}
      count-query (assoc :count (jdbc/get-result-count count-query)))))

;; SPECIAL

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
