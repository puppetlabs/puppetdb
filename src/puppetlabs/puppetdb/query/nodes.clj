(ns puppetlabs.puppetdb.query.nodes
  "Node query

   This implements the node query operations according to the [node query
   spec](../spec/node.md)."
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(defn node-columns
  "Return node columns based on version"
  [version]
  [:certname :deactivated :catalog_timestamp :facts_timestamp :report_timestamp
   :catalog_environment :facts_environment :report_environment])

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  ([version query]
     (query->sql version query {}))
  ([version query paging-options]
     {:pre  [((some-fn nil? sequential?) query)]
      :post [(map? %)
             (jdbc/valid-jdbc-query? (:results-query %))
             (or
              (not (:count? paging-options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}
     (paging/validate-order-by! (node-columns version) paging-options)
     (qe/compile-user-query->sql qe/nodes-query query paging-options)))

(pls/defn-validated munge-result-rows
  [_
   projected-fields :- [s/Keyword]
   _]
  (fn [rows]
    (if (empty? rows)
      []
      (map (qe/basic-project projected-fields) rows))))

(defn query-nodes
  "Search for nodes satisfying the given SQL filter."
  [version query-sql]
  {:pre  [(map? query-sql)
          (jdbc/valid-jdbc-query? (:results-query query-sql))]
   :post [(map? %)
          (sequential? (:result %))]}
  (let [{[sql & params] :results-query
         count-query    :count-query
         projections    :projections} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          (comp doall (munge-result-rows version projections)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn status
  "Given a node's name, return the current status of the node.  Results
  include whether it's active and the timestamp of its most recent catalog, facts,
  and report."
  [version node]
  {:pre  [string? node]}
  (let [sql (query->sql version ["=" "certname" node])
        results (:result (query-nodes version sql))]
    (first results)))
