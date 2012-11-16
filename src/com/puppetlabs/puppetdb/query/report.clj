;; ## SQL/query-related functions for reports

(ns com.puppetlabs.puppetdb.query.report
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [query-to-vec underscores->dashes valid-jdbc-query?]]))

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
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-report-term query)]
    (apply vector (format " WHERE %s" where) params)))

(defn query-reports
  "Take a query and its parameters, and return a vector of matching reports."
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [query   (format (str "SELECT hash,
                                      certname,
                                      puppet_version,
                                      report_format,
                                      configuration_version,
                                      start_time,
                                      end_time,
                                      receive_time
                                  FROM reports %s ORDER BY start_time DESC")
                    sql)
        results (map
                    #(utils/mapkeys underscores->dashes %)
                    (query-to-vec (apply vector query params)))]
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
    (throw (IllegalArgumentException.
                 (str term " is not a valid query term")))))

