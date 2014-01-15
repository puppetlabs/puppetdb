;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [com.puppetlabs.jdbc :as sql])
  (:use [com.puppetlabs.puppetdb.query :only [fact-query->sql fact-operators-v2 execute-query]]
        [com.puppetlabs.puppetdb.query.paging :only [validate-order-by!]]))

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
    {:post [(map? %)
            (coll? (:result %))
            (every? string? (:result %))]}
    (validate-order-by! [:name] paging-options)
    (let [facts (execute-query
                  ["SELECT DISTINCT name FROM certname_facts ORDER BY name"]
                  paging-options)]
      (update-in facts [:result] #(map :name %)))))

(defn facts-sql
  "Return a vector with the facts SQL query string as the first element, parameters
   needed for that query as the rest."
  [query paging-options]
  (if query
    (let [[subselect & params] (fact-query->sql fact-operators-v2 query)
          sql (format "SELECT facts.certname, facts.name, facts.value FROM (%s) facts" subselect)]
      (apply vector sql params))
    ["SELECT certname, name, value FROM certname_facts"]))

(defn query->sql
  "Compile a query into an SQL expression."
  [query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (let [[sql & params] (facts-sql query paging-options)]
    (conj {:results-query (apply vector (sql/paged-sql sql paging-options) params)}
          (when (:count? paging-options)
            [:count-query (apply vector (sql/count-sql sql) params)]))))

(defn with-queried-facts
  "Execute `func` against the rows returned from fact query `query`
  with query parameters `params`."
  [query paging-options params func]
  {:pre [(string? query)
         (or (coll? params) (nil? params))
         (fn? func)]}
  (validate-order-by! [:certname :name :value] paging-options)
  (sql/with-query-results-cursor query params rs
    (func rs)))
