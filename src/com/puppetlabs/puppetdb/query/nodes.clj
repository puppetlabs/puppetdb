;; ## Node query
;;
;; This implements the node query operations according to the [node query
;; spec](../spec/node.md).
;;
(ns com.puppetlabs.puppetdb.query.nodes
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

(defn node-columns
  "Return node columns based on version"
  [version]
  (case version
    (:v1 :v2 :v3)
    [:name :deactivated :catalog_timestamp :facts_timestamp :report_timestamp]

    [:certname :deactivated :catalog_timestamp :facts_timestamp :report_timestamp
     :catalog_environment :facts_environment :report_environment]))

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
   (case version
     (:v2 :v3)
     (let [operators (query/node-operators version)
           [subselect & params] (query/node-query->sql version operators query)
           sql (case version
                 (:v1 :v2 :v3)
                 (format "SELECT subquery1.name,
                                   subquery1.deactivated,
                                   catalogs.timestamp AS catalog_timestamp,
                                   factsets.timestamp AS facts_timestamp,
                                   reports.end_time AS report_timestamp
                            FROM (%s) subquery1
                            LEFT OUTER JOIN catalogs
                              ON subquery1.name = catalogs.certname
                            LEFT OUTER JOIN factsets
                              ON subquery1.name = factsets.certname
                            LEFT OUTER JOIN reports
                              ON subquery1.name = reports.certname
                                AND reports.hash
                                  IN (SELECT report FROM latest_reports)
                            ORDER BY subquery1.name ASC" subselect)

                   ;; For :v4 the query now all lives in node-query->sql
                 subselect)
           paged-select (jdbc/paged-sql sql paging-options)
           result {:results-query (apply vector paged-select params)}]
       (if (:count? paging-options)
         (assoc result :count-query (apply vector (jdbc/count-sql subselect) params))
         result))
     (qe/compile-user-query->sql
      qe/nodes-query query paging-options))))

(defn munge-result-rows
  [version]
  (case version
    (:v1 :v2 :v3) identity

    (fn [rows]
      (map
       #(kitchensink/mapkeys jdbc/underscores->dashes %)
       rows))))

(defn query-nodes
  "Search for nodes satisfying the given SQL filter."
  [version query-sql]
  {:pre  [(map? query-sql)
          (jdbc/valid-jdbc-query? (:results-query query-sql))]
   :post [(map? %)
          (sequential? (:result %))]}
  (let [{[sql & params] :results-query
         count-query    :count-query} query-sql
        result {:result (query/streamed-query-result
                         version sql params
                         (comp doall (munge-result-rows version)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn status
  "Given a node's name, return the current status of the node.  Results
  include whether it's active and the timestamp of its most recent catalog, facts,
  and report."
  [version node]
  {:pre  [string? node]}
  (let [sql     (query->sql version ["=" (case version (:v2 :v3) "name" "certname") node])
        results (:result (query-nodes version sql))]
    (first results)))
