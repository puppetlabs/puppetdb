(ns com.puppetlabs.puppetdb.query.aggregate-event-counts
  (:require [com.puppetlabs.puppetdb.query.event-counts :as event-counts])
  (:use [com.puppetlabs.jdbc :only [valid-jdbc-query? query-to-vec]]))

(defn- get-aggregate-sql
  ;; TODO docs
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
  ;; TODO docs
  ([query summarize-by]
   (query->sql query summarize-by {}))
  ([query summarize-by extra-query-params]
   {:pre  [(sequential? query)
           (string? summarize-by)
           (map? extra-query-params)]
    :post [(valid-jdbc-query? %)]}
   (let [[count-sql & params] (event-counts/query->sql query summarize-by extra-query-params)
         aggregate-sql        (get-aggregate-sql count-sql)]
     (apply vector aggregate-sql params))))

(defn query-aggregate-event-counts
  "Given a SQL query and its parameters, return a vector of matching results."
  [[sql & params]]
  {:pre  [(string? sql)]
   :post [(vector? %)]}
  (query-to-vec (apply vector sql params)))

