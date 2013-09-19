(ns com.puppetlabs.puppetdb.query.aggregate-event-counts
  (:require [com.puppetlabs.puppetdb.query.event-counts :as event-counts])
  (:use [com.puppetlabs.jdbc :only [valid-jdbc-query? query-to-vec]]
        [com.puppetlabs.utils :only [mapvals]]))

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
  "Convert an aggregate-event-counts `query` and a value to `summarize-by` into a SQL string.
  Since all inputs are forwarded to `event-counts/query->sql`, look there for proper documentation."
  ([query summarize-by]
   (query->sql query summarize-by {}))
  ([query summarize-by query-options]
   {:pre  [(sequential? query)
           (string? summarize-by)
           (map? query-options)]
    :post [(valid-jdbc-query? %)]}
   (let [[count-sql & params] (event-counts/query->sql query summarize-by query-options)
         aggregate-sql        (get-aggregate-sql count-sql)]
     (apply vector aggregate-sql params))))

(defn- perform-query
  "Given a SQL query and its parameters, return a vector of matching results."
  [[sql & params]]
  {:pre  [(string? sql)]
   :post [(vector? %)
          (= (count %) 1)]}
  (query-to-vec (apply vector sql params)))

(defn query-aggregate-event-counts
  "Given a SQL query and its parameters, return the single matching result map."
  [[sql & params :as query-and-params]]
  {:pre  [(string? sql)]
   :post [(map? %)]}
  (->> (perform-query query-and-params)
    first
    (mapvals #(if (nil? %) 0 %))))

