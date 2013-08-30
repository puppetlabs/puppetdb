;; ## Node query
;;
;; This implements the node query operations according to the [node query
;; spec](../spec/node.md).
;;
(ns com.puppetlabs.puppetdb.query.node
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string])
  (:use [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-array-query-string sql-as-numeric]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.puppetdb.query :only [node-query->sql node-operators-v1 node-operators-v2]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection valid-jdbc-query?]]
        [com.puppetlabs.utils :only [keyset parse-number]]))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [operators query]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(valid-jdbc-query? %)]}
  (let [[subselect & params] (if query
                               (node-query->sql operators query)
                               ["SELECT name, deactivated FROM certnames"])
        sql (format "SELECT subquery1.name,
                     subquery1.deactivated,
                     certname_catalogs.timestamp AS catalog_timestamp,
                     certname_facts_metadata.timestamp AS facts_timestamp,
                     (SELECT end_time FROM reports
                       WHERE certname = subquery1.name
                       ORDER BY end_time DESC LIMIT 1) AS report_timestamp
                     FROM (%s) subquery1
                       LEFT OUTER JOIN certname_catalogs ON subquery1.name = certname_catalogs.certname
                       LEFT OUTER JOIN certname_facts_metadata ON subquery1.name = certname_facts_metadata.certname
                     ORDER BY subquery1.name ASC"
                    subselect)]
    (apply vector sql params)))

(defn query-nodes
  "Search for nodes satisfying the given SQL filter."
  [filter-expr]
  {:pre  [(valid-jdbc-query? filter-expr)]
   :post [(vector? %)
          (every? #(= #{:name :deactivated :catalog_timestamp :facts_timestamp :report_timestamp} (keyset %)) %)]}
  (query-to-vec filter-expr))

(def v1-query->sql
  (partial query->sql node-operators-v1))

(def v2-query->sql
  (partial query->sql node-operators-v2))

(defn status
  "Given a node's name, return the current status of the node.  Results
  include whether it's active and the timestamp of its most recent catalog, facts,
  and report."
  [node]
  {:pre  [string? node]
   :post [(or (nil? %)
              (= #{:name :deactivated :catalog_timestamp :facts_timestamp :report_timestamp} (keyset %)))]}
  (let [sql     (query->sql node-operators-v2 ["=" "name" node])
        results (query-to-vec sql)]
    (first results)))
