;; ## Node query
;;
;; This implements the node query operations according to the [node query
;; spec](../spec/node.md).
;;
(ns com.puppetlabs.puppetdb.query.node
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string])
  (:use clojureql.core
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-array-query-string sql-as-numeric]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.puppetdb.query :only [node-query->sql node-operators-v1 node-operators-v2]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection valid-jdbc-query?]]
        [com.puppetlabs.utils :only [parse-number]]))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [operators query]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(valid-jdbc-query? %)]}
  (let [[subselect & params] (if query
                               (node-query->sql operators query)
                               ["SELECT name, deactivated FROM certnames"])
        sql (format "SELECT name AS certname FROM (%s) subquery1 ORDER BY name ASC" subselect)]
    (apply vector sql params)))

(def v1-query->sql
  (partial query->sql node-operators-v1))

(def v2-query->sql
  (partial query->sql node-operators-v2))

(defn query-nodes
  "Search for nodes satisfying the given SQL filter."
  [filter-expr]
  {:pre  [(valid-jdbc-query? filter-expr)]
   :post [(vector? %)
          (every? string? %)]}
  (mapv :certname (query-to-vec filter-expr)))
