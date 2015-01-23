(ns puppetlabs.puppetdb.query.reports
  (:require [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [schema.core :as s]
            [puppetlabs.puppetdb.query.events :refer [events-for-report-hash]]
            [clojure.set :refer [rename-keys]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.utils :as utils]))

(def row-schema
  {:hash String
   :certname String
   :puppet_version String
   :report_format s/Int
   :configuration_version String
   :start_time pls/Timestamp
   :end_time pls/Timestamp
   :receive_time pls/Timestamp
   :transaction_uuid String
   :event_status String
   :timestamp pls/Timestamp
   :resource_type String
   :resource_title String
   :new_value String
   :old_value String
   :status (s/maybe String)
   :property (s/maybe String)
   :message (s/maybe String)
   :file (s/maybe String)
   :line (s/maybe s/Int)
   :containment_path (s/maybe [String])
   (s/optional-key :environment) (s/maybe String)})

(def resource-event-schema
  {:new_value s/Any
   :old_value s/Any
   :resource_title String
   :resource_type String
   :timestamp pls/Timestamp
   :containment_path (s/maybe [String])
   :property (s/maybe String)
   :file (s/maybe String)
   :line (s/maybe s/Int)
   :status (s/maybe String)
   :message (s/maybe String)})

(def report-schema
  {:hash String
   (s/optional-key :environment) (s/maybe String)
   :certname String
   :puppet_version String
   :receive_time pls/Timestamp
   :start_time pls/Timestamp
   :end_time pls/Timestamp
   :report_format s/Int
   :configuration_version String
   :resource_events [resource-event-schema]
   :transaction_uuid String
   :status (s/maybe String)})

(def report-columns
  [:hash
   :puppet_version
   :receive_time
   :report_format
   :start_time
   :end_time
   :transaction_uuid
   :status
   :environment
   :configuration_version
   :certname])

(defn create-report-pred
  [rows]
  (let [report-hash (:hash (first rows))]
    (fn [row]
      (= report-hash (:hash row)))))

(defn collapse-resource-events
  [acc row]
  (let [resource-event (select-keys row [:containment_path :new_value
                                         :old_value :resource_title :resource_type
                                         :property :file :line :event_status :timestamp
                                         :message])]
    (into acc
          [(-> resource-event
               (update-in [:new_value] json/parse-string)
               (update-in [:old_value] json/parse-string)
               (rename-keys {:event_status :status}))])))

(pls/defn-validated collapse-report :- report-schema
  [version :- s/Keyword
   report-rows :- [row-schema]]
  (let [first-row (first report-rows)
        resource-events (->> report-rows
                             (reduce collapse-resource-events []))]
    (assoc (select-keys first-row report-columns)
      :resource_events resource-events)))

(pls/defn-validated structured-data-seq
  "Produce a lazy seq of catalogs from a list of rows ordered by catalog hash"
  [version :- s/Keyword
   rows]
  (utils/collapse-seq create-report-pred
                      #(collapse-report version %)
                      rows))

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

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   projections]
  (fn [rows]
    (if (empty? rows)
      []
      (map (qe/basic-project projections)
           (structured-data-seq version rows)))))

(defn query-reports
  "Queries reports and unstreams, used mainly for testing.

  This wraps the existing streaming query code but returns results
  and count (if supplied)."
  [version query-sql]
  {:pre [(map? query-sql)]}
  (let [{[sql & params] :results-query
         count-query    :count-query
         projections    :projections} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                          (comp doall (munge-result-rows version projections)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query :reports))
      result)))


(defn reports-for-node
  "Return reports for a particular node."
  [version node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (seq? %))]}
  (let [query ["=" "certname" node]
        reports (->> (query->sql version query)
                     (query-reports version)
                     ;; We don't support paging in this code path, so we
                     ;; can just pull the results out of the return value
                     :result)]
    (map
     #(merge % {:resource_events (events-for-report-hash version (get % :hash))})
     reports)))

(defn report-for-hash
  "Convenience function; given a report hash, return the corresponding report object
  (without events)."
  [version hash]
  {:pre  [(string? hash)]
   :post [(or (nil? %)
              (map? %))]}
  (let [query ["=" "hash" hash]]
    (->> (query->sql version query)
         (query-reports version)
         ;; We don't support paging in this code path, so we
         ;; can just pull the results out of the return value
         (:result)
         (first))))

(defn is-latest-report?
  "Given a node and a report hash, return `true` if the report is the most recent one for the node,
  and `false` otherwise."
  [node report-hash]
  {:pre  [(string? node)
          (string? report-hash)]
   :post [(kitchensink/boolean? %)]}
  (= 1 (count (jdbc/query-to-vec
               ["SELECT report FROM latest_reports
                    WHERE certname = ? AND report = ?"
                node report-hash]))))
