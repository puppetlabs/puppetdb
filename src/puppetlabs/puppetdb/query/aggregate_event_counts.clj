(ns puppetlabs.puppetdb.query.aggregate-event-counts
  (:require [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :as kitchensink]))

(defn- get-aggregate-sql
  "Given the `event-count-sql`, return a SQL string that will aggregate the results."
  [event-count-sql]
  {:pre  [(string? event-count-sql)]
   :post [(string? %)]}
  (format "SELECT SUM(CASE WHEN successes > 0 THEN 1 ELSE 0 END) as successes,
                  SUM(CASE WHEN failures > 0  THEN 1 ELSE 0 END) as failures,
                  SUM(CASE WHEN noops > 0 THEN 1 ELSE 0 END) as noops,
                  SUM(CASE WHEN skips > 0 THEN 1 ELSE 0 END) as skips,
                  COUNT(*) as total
           FROM (%s) event_counts" event-count-sql))

(defn query->sql
  "Convert an aggregate-event-counts `query` and a value to `summarize_by` into a SQL string.
  Since all inputs are forwarded to `event-counts/query->sql`, look there for proper documentation."
  [version query [summarize_by query-options]]
  {:pre  [(sequential? query)
          (string? summarize_by)
          ((some-fn map? nil?) query-options)]
   :post [(jdbc/valid-jdbc-query? (:results-query %))]}
  (let [query-options (if (nil? query-options) {} query-options)
        [count-sql & params] (:results-query
                              (event-counts/query->sql version query [summarize_by query-options {}]))
        aggregate-sql        (get-aggregate-sql count-sql)]
    {:results-query (apply vector aggregate-sql params)}))

(defn- perform-query
  "Given a SQL query and its parameters, return a vector of matching results."
  [[sql & params]]
  {:pre  [(string? sql)]
   :post [(vector? %)
          (= (count %) 1)]}
  (jdbc/query-to-vec (apply vector sql params)))

(defn query-aggregate-event-counts
  "Given a SQL query and its parameters, return the single matching result map."
  [{:keys [results-query]}]
  {:pre  [(string? (first results-query))]
   :post [(map? %)]}
  (->> (perform-query results-query)
       first
       (kitchensink/mapvals #(if (nil? %) 0 %))))
