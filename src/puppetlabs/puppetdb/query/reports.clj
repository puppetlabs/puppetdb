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
            [puppetlabs.puppetdb.scf.storage-utils :as scf-utils]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s])
  (:import  [org.postgresql.util PGobject]))

;; MUNGE

(pls/defn-validated rtj->event :- reports/resource-event-query-schema
  "Convert row_to_json format to real data."
  [event :- {s/Str s/Any}]
  (-> event
      (set/rename-keys {"f1" :status
                        "f2" :timestamp
                        "f3" :resource_type
                        "f4" :resource_title
                        "f5" :property
                        "f6" :new_value
                        "f7" :old_value
                        "f8" :message
                        "f9" :file
                        "f10" :line
                        "f11" :containment_path
                        "f12" :containing_class})
      (update-in [:old_value] json/parse-string)
      (update-in [:new_value] json/parse-string)))

(pls/defn-validated events->expansion :- {:href s/Str (s/optional-key :data) [s/Any]}
  "Convert events to the expanded format."
  [obj :- (s/maybe PGobject)
   hash :- s/Str
   base-url :- s/Str]
  (let [data-obj {:href (str base-url "/reports/" hash "/events")}]
    (if obj
      (assoc data-obj :data (map rtj->event
                                 (json/parse-string (.getValue obj))))
      data-obj)))

(pls/defn-validated logs->expansion :- {:href s/Str (s/optional-key :data) [s/Any]}
  "Convert logs to the expanded format."
  [data :- (s/maybe (s/either PGobject s/Str))
   hash :- s/Str
   base-url :- s/Str]
  (let [parse-json (scf-utils/parse-db-json-fn)
        data-obj {:href (str base-url "/reports/" hash "/logs")}]
    (if data
      (assoc data-obj :data (parse-json data))
      data-obj)))

(pls/defn-validated metrics->expansion :- {:href s/Str (s/optional-key :data) [s/Any]}
  "Convert metrics data to the expanded format."
  [data :- (s/maybe (s/either PGobject s/Str))
   hash :- s/Str
   base-url :- s/Str]
  (let [parse-json (scf-utils/parse-db-json-fn)
        data-obj {:href (str base-url "/reports/" hash "/metrics")}]
    (if data
      (assoc data-obj :data (parse-json data))
      data-obj)))

(pls/defn-validated row->report
  "Convert a report query row into a final report format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:resource_events] events->expansion (:hash row) base-url)
        (utils/update-when [:metrics] metrics->expansion (:hash row) base-url)
        (utils/update-when [:logs] logs->expansion (:hash row) base-url))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   projected-fields :- [s/Keyword]
   _
   url-prefix :- s/Str]
  (let [base-url (str url-prefix "/" (name version))]
    (fn [rows]
      (map (comp (qe/basic-project projected-fields)
                 (row->report base-url))
           rows))))

;; QUERY

(def report-columns
  [:hash
   :puppet_version
   :receive_time
   :report_format
   :start_time
   :end_time
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
  (let [{[sql & params] :results-query
         count-query    :count-query
         projections    :projections} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                          (comp doall (munge-result-rows version projections {} url-prefix)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

;; SPECIAL

(defn is-latest-report?
  "Given a node and a report hash, return `true` if the report is the most recent one for the node,
  and `false` otherwise."
  [node report-hash]
  {:pre  [(string? node)
          (string? report-hash)]
   :post [(kitchensink/boolean? %)]}
  (= 1 (count (jdbc/query-to-vec
               ["SELECT reports.hash as latest_report_hash
                 FROM certnames
                 INNER JOIN reports ON reports.id = certnames.latest_report_id
                 WHERE certnames.certname = ? AND reports.hash = ?"
                node report-hash]))))
