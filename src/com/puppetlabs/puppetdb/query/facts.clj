;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [com.puppetlabs.jdbc :as sql])
  (:use clojureql.core
        [com.puppetlabs.puppetdb.query :only [fact-query->sql fact-operators-v2]]))

(defn facts-for-node
  "Fetch the facts for the given node, as a map of `{fact value}`. This is used
  for the deprecated v1 facts API."
  [node]
  {:pre  [(string? node)]
   :post [(map? %)]}
  (let [facts (-> (table :certname_facts)
                  (project [:name, :value])
                  (select (where (= :certname node))))]
    (into {} (for [fact @facts]
               [(:name fact) (:value fact)]))))

(defn flat-facts-by-node
  "Similar to `facts-for-node`, but returns facts in the form:

    [{:certname <node> :name <fact> :value <value>}
     ...
     {:certname <node> :name <fact> :value <value>}]"
  [node]
  (-> (table :certname_facts)
      (project [:certname :name :value])
      (select (where (= :certname node)))
      (deref)))

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated nodes."
  []
  {:post [(coll? %)
          (every? string? %)]}
  (let [facts (-> (table :certname_facts)
                  (project [:name])
                  (distinct)
                  (order-by [:name]))]
    (map :name @facts)))

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

(defn with-queried-facts
  "Execute `func` against the rows returned from fact query `query`
  with query parameters `params`."
  [query params func]
  {:pre [(string? query)
         (or (coll? params) (nil? params))
         (fn? func)]}
  (sql/with-query-results-cursor query params rs
    (func rs)))

(defn query-facts
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [results (atom [])]
    (with-queried-facts sql params #(reset! results (vec %)))
    @results))
