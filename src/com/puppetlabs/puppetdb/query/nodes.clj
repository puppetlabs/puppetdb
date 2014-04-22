;; ## Node query
;;
;; This implements the node query operations according to the [node query
;; spec](../spec/node.md).
;;
(ns com.puppetlabs.puppetdb.query.nodes
  (:refer-clojure :exclude [compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.jdbc :as jdbc])
  (:use [com.puppetlabs.puppetdb.scf.storage-utils :only [db-serialize sql-array-query-string sql-as-numeric]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.puppetdb.query :only [node-query->sql node-operators execute-query]]
        [com.puppetlabs.puppetdb.query.paging :only [validate-order-by!]]))

(defn node-columns
  "Return node columns based on version"
  [version]
  (let [base [:name :deactivated
              :catalog_timestamp :facts_timestamp :report_timestamp]]
    (case version
      (:v1 :v2 :v3) base
      (concat base [:catalog_last_environment :facts_last_environment :report_last_environment]))))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [version query]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(jdbc/valid-jdbc-query? %)]}
  (let [operators (node-operators version)
        [subselect & params] (node-query->sql version operators query)
        sql (case version
              (:v1 :v2 :v3)
              (format "SELECT subquery1.name,
                       subquery1.deactivated,
                       catalogs.timestamp AS catalog_timestamp,
                       certname_facts_metadata.timestamp AS facts_timestamp,
                       reports.end_time AS report_timestamp
                       FROM (%s) subquery1
                         LEFT OUTER JOIN catalogs
                           ON subquery1.name = catalogs.certname
                         LEFT OUTER JOIN certname_facts_metadata
                           ON subquery1.name = certname_facts_metadata.certname
                         LEFT OUTER JOIN reports
                           ON subquery1.name = reports.certname
                             AND reports.hash
                               IN (SELECT report FROM latest_reports)
                       ORDER BY subquery1.name ASC" subselect)

              ;; For :v4 the query now all lives in node-query->sql
              subselect)]
    (apply vector sql params)))

(defn query-nodes
  "Search for nodes satisfying the given SQL filter."
  ([version filter-expr] (query-nodes version filter-expr nil))
  ([version filter-expr paging-options]
     {:pre  [(jdbc/valid-jdbc-query? filter-expr)]
      :post [(map? %)
             (sequential? (:result %))]}
     (validate-order-by! (node-columns version) paging-options)
     (let [results (execute-query filter-expr paging-options)]
       (case version
         (:v1 :v2 :v3)
         results

         (assoc results :result
                (map
                 #(kitchensink/mapkeys jdbc/underscores->dashes %)
                 (:result results)))))))

(defn status
  "Given a node's name, return the current status of the node.  Results
  include whether it's active and the timestamp of its most recent catalog, facts,
  and report."
  [version node]
  {:pre  [string? node]
   :post [(or (nil? %)
              (= (set (node-columns version)) (kitchensink/keyset %)))]}
  (let [sql     (query->sql version ["=" "name" node])
        results (jdbc/query-to-vec sql)]
    (first results)))
