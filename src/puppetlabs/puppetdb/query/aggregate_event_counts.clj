(ns puppetlabs.puppetdb.query.aggregate-event-counts
  (:require [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [clojure.string :as str]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :as kitchensink]))

(defn- get-aggregate-sql
  "Given the `event-count-sql`, return a SQL string that will aggregate the results."
  [event-count-sql summarize_by]
  {:pre  [(string? event-count-sql)
          (contains? #{"resource" "containing_class" "certname"} summarize_by)]
   :post [(string? %)]}
  (format "(SELECT '%s' as summarize_by,
           SUM(CASE WHEN successes > 0 THEN 1 ELSE 0 END) as successes,
                  SUM(CASE WHEN failures > 0  THEN 1 ELSE 0 END) as failures,
                  SUM(CASE WHEN noops > 0 THEN 1 ELSE 0 END) as noops,
                  SUM(CASE WHEN skips > 0 THEN 1 ELSE 0 END) as skips,
                  COUNT(*) as total
           FROM (%s) event_counts)" summarize_by event-count-sql))

(defn- assemble-aggregate-sql
  "Convert an aggregate-event-counts `query` and a value to `summarize_by`
   into a SQL string."
  [version query {:keys [summarize_by] :as query-options}]
  {:pre  [((some-fn nil? sequential?) query)
          (string? summarize_by)
          ((some-fn map? nil?) query-options)]}
  (let [query-options (if (nil? query-options) {} query-options)
        [count-sql & params] (:results-query
                               (event-counts/query->sql
                                 true
                                 version
                                 query
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
        params (if distinct_resources
                 ;; when distinct resources is used, the first two parameters
                 ;; are the distinct start/end times.
                 ;; extract the distinct start/end times and duplicate the rest
                 ;; as many times as there are summarize_by's
                 (concat (take 2 common-params)
                         (flatten (repeat nsummarized (drop 2 common-params))))
                 ;; repeat all params as many times as there are summarize_by's
                 (flatten (repeat nsummarized common-params)))
        unioned-sql (cond-> (str/join " UNION ALL " (map first aggregated-sql-and-params))
                      distinct_resources events/with-latest-events)]
    {:results-query (apply vector unioned-sql params)}))

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
