;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [com.puppetlabs.jdbc :as sql])
  (:use [com.puppetlabs.puppetdb.query :only [fact-query->sql fact-operators-v2]]
        [com.puppetlabs.middleware.paging :only [validate-order-by!]]))

(defn facts-for-node
  "Fetch the facts for the given node, as a map of `{fact value}`. This is used
  for the deprecated v1 facts API."
  [node]
  {:pre  [(string? node)]
   :post [(map? %)]}
  (let [facts (sql/query-to-vec
                ["SELECT name, value FROM certname_facts WHERE certname = ?"
                 node])]
    (into {} (for [fact facts]
               [(:name fact) (:value fact)]))))

(defn flat-facts-by-node
  "Similar to `facts-for-node`, but returns facts in the form:

    [{:certname <node> :name <fact> :value <value>}
     ...
     {:certname <node> :name <fact> :value <value>}]"
  [node]
  (sql/query-to-vec
    ["SELECT certname, name, value FROM certname_facts WHERE certname = ?"
     node]))

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated nodes."
  ([]
    (fact-names {}))
  ([paging-options]
    {:post [(coll? %)
            (every? string? %)]}
    (validate-order-by! [:name] paging-options)
    (let [facts (sql/paged-query-to-vec
                  ["SELECT DISTINCT name FROM certname_facts ORDER BY name"]
                  paging-options)]
      (map :name facts))))

(defn query->sql
  "Compile a query into an SQL expression."
  [query]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) (rest %))]}
  (if query
    (let [[subselect & params] (fact-query->sql fact-operators-v2 query)
          sql (format "SELECT facts.certname, facts.name, facts.value FROM (%s) facts ORDER BY facts.certname, facts.name, facts.value" subselect)]
      (apply vector sql params))
    ["SELECT certname, name, value FROM certname_facts ORDER BY certname, name, value"]))

(defn query-facts
  [[sql & params] paging-options]
  {:pre [(string? sql)]}
  (validate-order-by! [:certname :name :value] paging-options)
  (sql/paged-query-to-vec (concat [sql] params)
    paging-options))
