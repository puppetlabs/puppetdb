;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.report
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.puppetdb.query.utils :only [valid-query-format? sql-to-wire]]))



;; ## Report query functions
;;
;; The following functions provide the basic logic for composing and executing
;; queries against reports (report summaries / metadata).

(defmulti compile-report-term
  "Recursively compile a report query into a structured map reflecting the terms
  of the query."
  (fn [query]
    (let [operator (string/lower-case (first query))]
      (cond
        (#{"="} operator) :equality
        ))))

(defn report-query->sql
  "Compile a report query into an SQL expression."
  [query]
  {:pre [(vector? query)]
   :post [(valid-query-format? %)]}
  (let [{:keys [where params]} (compile-report-term query)]
    (apply vector (format " WHERE %s" where) params)))

(defn query-reports
  "Take a query and its parameters, and return a vector of matching reports."
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [query   (format (str "SELECT id,
                                      certname,
                                      puppet_version,
                                      report_format,
                                      configuration_version,
                                      start_time,
                                      end_time,
                                      receive_time
                                  FROM reports %s ORDER BY start_time DESC")
    sql)
        results   (map sql-to-wire (query-to-vec (apply vector query params)))]
    results))


(defmethod compile-report-term :equality
  [[op path value :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
               (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (case path
    "certname" {:where "reports.certname = ?"
                :params [value] }
    :else (throw (IllegalArgumentException.
                   (str term " is not a valid query term")))))

;; ## Resource Event query functions
;;
;; The following functions provide the basic logic for composing and executing
;; queries against resource events (the individual events that make up a report).

(defmulti compile-resource-event-term
  "Recursively compile a resource event query into a structured map reflecting
  the terms of the query."
  (fn [query]
    (let [operator (string/lower-case (first query))]
      (cond
        (#{"="} operator) :equality
        ))))

(defn resource-event-query->sql
  "Compile a resource event query into an SQL expression."
  [query]
  {:pre [(vector? query)]
   :post [(valid-query-format? %)]}
  (let [{:keys [where params]} (compile-resource-event-term query)]
    (apply vector (format " WHERE %s" where) params)))

(defn query-resource-events
  "Take a query and its parameters, and return a vector of matching resource
  events."
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [query   (format (str "SELECT report_id,
                                      status,
                                      timestamp,
                                      resource_type,
                                      resource_title,
                                      property,
                                      new_value,
                                      old_value,
                                      message
                                  FROM resource_events %s")
                        sql)
        results   (map sql-to-wire (query-to-vec (apply vector query params)))]
    results))

(defmethod compile-resource-event-term :equality
  [[op path value :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
               (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (case path
    "report-id" {:where "resource_events.report_id = ?"
                 :params [value] }
    :else (throw (IllegalArgumentException.
                   (str term " is not a valid query term")))))
