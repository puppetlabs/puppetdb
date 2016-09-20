(ns puppetlabs.puppetdb.query.aggregate-event-counts
  (:require [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query :as query]
            [clojure.string :as str]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage :refer [store-corrective-change?]]))

(defn- get-aggregate-sql
  "Given the `event-count-sql`, return a SQL string that will aggregate the results."
  [event-count-sql summarize_by]
  {:pre  [(string? event-count-sql)
          (contains? #{"resource" "containing_class" "certname"} summarize_by)]
   :post [(string? %)]}
  (format (str "(SELECT '%s' as summarize_by,
                        SUM(CASE WHEN failures > 0  THEN 1 ELSE 0 END) as failures,
                        SUM(CASE WHEN skips > 0 THEN 1 ELSE 0 END) as skips, "
               (if @store-corrective-change?
                 "      SUM(CASE WHEN intentional_successes > 0 THEN 1 ELSE 0 END) as intentional_successes,
                        SUM(CASE WHEN corrective_successes > 0 THEN 1 ELSE 0 END) as corrective_successes,
                        SUM(CASE WHEN intentional_noops > 0 THEN 1 ELSE 0 END) as intentional_noops,
                        SUM(CASE WHEN corrective_noops > 0 THEN 1 ELSE 0 END) as corrective_noops, "
                 "      SUM(CASE WHEN successes > 0 THEN 1 ELSE 0 END) as successes,
                        SUM(CASE WHEN noops > 0 THEN 1 ELSE 0 END) as noops, ")
               "        COUNT(*) as total
                   FROM (%s) event_counts)")
          summarize_by
          event-count-sql))

(defn- assemble-aggregate-sql
  "Convert an aggregate-event-counts `query` and a value to `summarize_by`
   into a SQL string."
  [version query {:keys [summarize_by distinct_resources] :as query-options}]
  {:pre  [((some-fn nil? sequential?) query)
          (string? summarize_by)
          ((some-fn map? nil?) query-options)]}
  (let [query-options (if (nil? query-options) {} query-options)
        [count-sql & params] (:results-query
                               (event-counts/query->sql
                                 true
                                 version
                                 (when-not distinct_resources query)
                                 query-options))]
    (vector (get-aggregate-sql count-sql summarize_by) params)))

(defn query->sql
  "Convert an aggregate-event-counts `query` and a value(s) to `summarize_by`
   into a SQL string. Since all inputs are forwarded to
   `event-counts/query->sql`, look there for proper documentation."
  [version query {:keys [distinct_resources summarize_by] :as query-options}]
  {:pre  [((some-fn nil? sequential?) query)
          ((some-fn map? nil?) query-options)]
   :post [(jdbc/valid-jdbc-query? (:results-query %))]}
  (let [summary-vec (str/split summarize_by #",")
        nsummarized (count summary-vec)
        aggregate-fn #(assemble-aggregate-sql
                        version query (assoc query-options :summarize_by %))
        aggregated-sql-and-params (map aggregate-fn summary-vec)
        common-params (or (second (first aggregated-sql-and-params)) [])
        {:keys [where params latest-report-clause]} (query/compile-term
                                                      (query/resource-event-ops version)
                                                      query)
        unioned-sql (cond-> (str/join " UNION ALL " (map first aggregated-sql-and-params))
                      distinct_resources (events/with-latest-events where latest-report-clause))
        query-params (if distinct_resources
                       (concat common-params params)
                       (flatten (repeat nsummarized common-params)))]
    {:results-query (apply vector unioned-sql query-params)}))

(defn- perform-query
  "Given a SQL query and its parameters, return a vector of matching results."
  [[sql & params]]
  {:pre  [(string? sql)]
   :post [(vector? %)]}
  (jdbc/query-to-vec (apply vector sql params)))

(defn munge-result-rows
  [_ _]
  (fn [rows]
    (map (partial kitchensink/mapvals #(if (nil? %) 0 %)) rows)))

(defn query-aggregate-event-counts
  "Given a SQL query and its parameters, return the single matching result map."
  [{:keys [results-query]}]
  {:pre  [(string? (first results-query))]
   :post [(seq? %)]}
  (->> (perform-query results-query)
       ((munge-result-rows nil nil))))
