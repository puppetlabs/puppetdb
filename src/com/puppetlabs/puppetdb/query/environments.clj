(ns com.puppetlabs.puppetdb.query.environments
  (:require [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.jdbc :refer [valid-jdbc-query?]]
            [com.puppetlabs.puppetdb.query.paging :refer [validate-order-by!]]
            [puppetlabs.kitchensink.core :as ks]))

(def environments-columns
  [:name])

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
   return nodes matching the `query`."
  [version query]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(valid-jdbc-query? %)]}
  (let [operators (query/environments-operators version)]
    (query/environments-query->sql operators query)))

(defn query-environments
  "Search for environments satisfying the given SQL filter."
  ([version filter-expr] (query-environments version filter-expr nil))
  ([version filter-expr paging-options]
     {:pre  [((some-fn nil? sequential?) filter-expr)]
      :post [(map? %)
             (vector? (:result %))
             (every? #(= (set environments-columns) (ks/keyset %)) (:result %))]}
     (validate-order-by! environments-columns paging-options)
     (query/execute-query (query->sql version filter-expr) paging-options)))
