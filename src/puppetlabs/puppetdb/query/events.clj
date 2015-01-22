(ns puppetlabs.puppetdb.query.events
  "SQL/query-related functions for events"
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as string]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.utils :as utils]))

(defn default-select
  "Build the default SELECT statement that we use in the common case.  Returns
  a two-item vector whose first value is the SQL string and whose second value
  is a list of parameters for the SQL query."
  [select-fields where params]
  {:pre [(string? select-fields)
         (string? where)
         ((some-fn nil? sequential?) params)]
   :post [(vector? %)
          (= 2 (count %))
          (string? (first %))
          ((some-fn nil? sequential?) (second %))]}
  [(format
    "SELECT %s
         FROM resource_events
         JOIN reports ON resource_events.report = reports.hash
         LEFT OUTER JOIN environments on reports.environment_id = environments.id
         WHERE %s"
    select-fields
    where)
   params])

(defn distinct-select
  "Build the SELECT statement that we use in the `distinct-resources` case (where
  we are filtering out multiple events on the same resource on the same node).
  Returns a two-item vector whose first value is the SQL string and whose second value
  is a list of parameters for the SQL query."
  [select-fields where params distinct-start-time distinct-end-time]
  {:pre [(string? select-fields)
         (string? where)
         ((some-fn nil? sequential?) params)]
   :post [(vector? %)
          (= 2 (count %))
          (string? (first %))
          ((some-fn nil? sequential?) (second %))]}
  [(format
    "SELECT %s
         FROM resource_events
         JOIN reports ON resource_events.report = reports.hash
         LEFT OUTER JOIN environments ON reports.environment_id = environments.id
         JOIN (SELECT reports.certname,
                      resource_events.resource_type,
                      resource_events.resource_title,
                      resource_events.property,
                      MAX(resource_events.timestamp) AS timestamp
                  FROM resource_events
                  JOIN reports ON resource_events.report = reports.hash
                  WHERE resource_events.timestamp >= ?
                     AND resource_events.timestamp <= ?
                  GROUP BY certname, resource_type, resource_title, property) latest_events
              ON reports.certname = latest_events.certname
               AND resource_events.resource_type = latest_events.resource_type
               AND resource_events.resource_title = latest_events.resource_title
               AND ((resource_events.property = latest_events.property) OR
                    (resource_events.property IS NULL AND latest_events.property IS NULL))
               AND resource_events.timestamp = latest_events.timestamp
         WHERE %s"
    select-fields
    where)
   (concat [distinct-start-time distinct-end-time] params)])

(defn legacy-query->sql
  "Compile a resource event `query` into an SQL expression."
  [version query-options query paging-options]
  {:pre  [(or (sequential? query) (nil? query))
          (let [distinct-options [:distinct-resources? :distinct-start-time :distinct-end-time]]
            (or (not-any? #(contains? query-options %) distinct-options)
                (every? #(contains? query-options %) distinct-options)))]
   :post [(map? %)
          (jdbc/valid-jdbc-query? (:results-query %))
          (or
           (not (:count? paging-options))
           (jdbc/valid-jdbc-query? (:count-query %)))]}
  (let [{:keys [where params]}  (query/compile-term (query/resource-event-ops version) query)
        select-fields           (string/join ", "
                                             (map
                                              (fn [[column [table alias]]]
                                                (str table "." column
                                                     (if alias (format " AS %s" alias) "")))
                                              query/event-columns))
        [sql params]            (if (:distinct-resources? query-options)
                                  (distinct-select select-fields where params
                                                   (:distinct-start-time query-options)
                                                   (:distinct-end-time query-options))
                                  (default-select select-fields where params))
        paged-select (jdbc/paged-sql sql paging-options)
        result {:results-query (apply vector paged-select params)}]
    (if (:count? paging-options)
      (assoc result :count-query (apply vector (jdbc/count-sql sql) params))
      result)))


(defn query->sql
  "Compile a resource event `query` into an SQL expression."
  [version query [query-options paging-options]]
  {:pre  [(or (sequential? query) (nil? query))
          (let [distinct-options [:distinct-resources? :distinct-start-time :distinct-end-time]]
            (or (not-any? #(contains? query-options %) distinct-options)
                (every? #(contains? query-options %) distinct-options)))]
   :post [(map? %)
          (jdbc/valid-jdbc-query? (:results-query %))
          (or
           (not (:count? paging-options))
           (jdbc/valid-jdbc-query? (:count-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/event-columns)) paging-options)
  (if (:distinct-resources? query-options)
    ;; The new query engine does not support distinct-resources yet, so we
    ;; fall back to the old
    (legacy-query->sql version query-options query paging-options)
    (qe/compile-user-query->sql qe/report-events-query query paging-options)))

;; Below this line the code is more about turning a query into results,
;; above is the SQL engine code (as it stands).

(defn munge-result-rows
  "Returns a function that munges the resulting rows ready for final
  presentation.

  Version is provided to alter the munge function depending on the API query."
  [version _]
  (fn [rows]
    (map
     ;; TODO: conversion to underscore should be standard anyway
     ;; at least for V4. Consider moving this operation to
     ;; query/streamed-query-result in the future.
     #(-> (kitchensink/mapkeys jdbc/underscores->dashes %)
          (utils/update-when [:old-value] json/parse-string)
          (utils/update-when [:new-value] json/parse-string))
     rows)))

(defn query-resource-events
  "Queries resource events and unstreams, used mainly for testing.

  This wraps the existing streaming query code but returns results
  and count (if supplied)."
  [version query-sql]
  {:pre [(map? query-sql)]}
  (let [{[sql & params] :results-query
         count-query    :count-query
         projections    :projections} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                          (comp doall (munge-result-rows version projections)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn events-for-report-hash
  "Given a particular report hash, this function returns all events for that
   given hash."
  [version report-hash]
  {:pre [(string? report-hash)]
   :post [(vector? %)]}
  (let [query          ["=" "report" report-hash]
        ;; we aren't actually supporting paging through this code path for now
        paging-options {}]
    (->> (query->sql version query [nil paging-options])
         (query-resource-events version)
         :result
         (mapv #(dissoc %
                        :run-start-time
                        :run-end-time
                        :report-receive-time
                        :environment)))))
