(ns com.puppetlabs.puppetdb.query.eventcounts
  (:require [com.puppetlabs.puppetdb.query.event :as events])
  (:use [com.puppetlabs.jdbc :only [valid-jdbc-query? query-to-vec]]))

(def sql-string "SELECT %s,
                  SUM(CASE WHEN status = 'failure' THEN 1 ELSE 0 END) AS failures,
                  SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successes,
                  SUM(CASE WHEN status = 'noop' THEN 1 ELSE 0 END) AS noops,
                  SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skips
                FROM (%s) events
                GROUP BY %s")

(defn- get-group-by
  ;; TODO docs
  [summarize-by]
  (condp = summarize-by
    "certname" "certname"
    "containing-class" "containing_class"
    "resource" "resource_type, resource_title"
    (throw (IllegalArgumentException.
             (format "Unsupported value for 'summarize-by': '%s'" summarize-by)))))

(defn query->sql
  ;; TODO docs
  [query summarize-by]
  {:pre  [(vector? query)]
   :post [(valid-jdbc-query? %)]}
  (let [group-by                   (get-group-by summarize-by)
        [event-sql & event-params] (events/query->sql query)
        sql                        (format sql-string group-by event-sql group-by)]
    (apply vector sql event-params)))

(defn query-event-counts
  ;; TODO docs
  [[sql & params]]
  {:pre [(string? sql)]}
  (query-to-vec (apply vector sql params)))
