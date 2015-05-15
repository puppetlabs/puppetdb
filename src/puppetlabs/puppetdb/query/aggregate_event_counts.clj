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
  (-> (event-counts/query->sql version query [summarize_by (or query-options {}) {}])
      (select-keys [:results-query])
      (update-in [:results-query 0] get-aggregate-sql)))

(def munge-result-row
  (partial kitchensink/mapvals (fnil identity 0)))

(defn munge-result-rows [_ _]
  (comp munge-result-row first))

(defn query-aggregate-event-counts
  "Given a SQL query and its parameters, return the single matching result map."
  [{:keys [results-query]}]
  {:pre  [(string? (first results-query))]
   :post [(map? %)]}
  (let [munge-fn (munge-result-rows nil nil)]
    (-> (jdbc/query-to-vec results-query)
        munge-fn)))
