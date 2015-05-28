(ns puppetlabs.puppetdb.query.environments
  (:require [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng.engine :as qe]))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
   return nodes matching the `query`."
  ([version query]
     (query->sql version query {}))
  ([version query paging-options]
     {:pre [((some-fn nil? sequential?) query)]
      :post [(map? %)
             (jdbc/valid-jdbc-query? (:results-query %))
             (or
              (not (:count? paging-options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}
     (qe/compile-user-query->sql qe/environments-query query paging-options)))

(defn query-environments
  "Search for environments satisfying the given SQL filter."
  [version query-sql]
  {:pre  [(map? query-sql)
          (jdbc/valid-jdbc-query? (:results-query query-sql))]
   :post [(map? %)
          (sequential? (:result %))]}
  (let [{:keys [count-query results-query]} query-sql
         result {:result (jdbc/with-query-results-cursor results-query doall)}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn status
  "Given an environment's name, return the results for the single environment."
  [version environment]
  {:pre  [string? environment]}
  (let [sql     (query->sql version ["=" "name" environment])
        results (:result (query-environments version sql))]
    (first results)))
