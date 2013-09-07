;; ## SQL/query-related functions for reports

(ns com.puppetlabs.puppetdb.query.report
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [query-to-vec paged-query-to-vec underscores->dashes valid-jdbc-query?]]
        [com.puppetlabs.puppetdb.query.event :only [events-for-report-hash]]
        [com.puppetlabs.middleware.paging :only [validate-order-by!]]))

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
  {:pre [(sequential? query)]
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-report-term query)]
    (apply vector (format " WHERE %s" where) params)))

(defn query-reports
  "Take a query and its parameters, and return a vector of matching reports."
  ([sql-and-params] (query-reports {} sql-and-params))
  ([paging-options [sql & params]]
    {:pre [(string? sql)]}
    (let [columns [:hash :certname :puppet-version :report-format
                   :configuration-version :start-time :end-time
                   :receive-time :transaction-uuid]]
      (validate-order-by! columns paging-options))
    (let [query   (format "SELECT hash,
                                        certname,
                                        puppet_version,
                                        report_format,
                                        configuration_version,
                                        start_time,
                                        end_time,
                                        receive_time,
                                        transaction_uuid
                                    FROM reports %s ORDER BY start_time DESC"
                      sql)
          results (map
                      #(utils/mapkeys underscores->dashes %)
                      (paged-query-to-vec (apply vector query params)
                        paging-options))]
      results)))


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
    "hash"     {:where "reports.hash = ?"
                :params [value]}
    (throw (IllegalArgumentException.
                 (str term " is not a valid query term")))))

(defn reports-for-node
  "Return reports for a particular node."
  [node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (seq? %))]}
  (let [query ["=" "certname" node]
        reports (-> query (report-query->sql) (query-reports))]
    (map
      #(merge % {:resource-events (events-for-report-hash (get % :hash))})
      reports)))

(defn is-latest-report?
  "Given a node and a report hash, return `true` if the report is the most recent one for the node,
  and `false` otherwise."
  [node report-hash]
  {:pre  [(string? node)
          (string? report-hash)]
   :post [(utils/boolean? %)]}
  (= 1 (count (query-to-vec
                ["SELECT report FROM latest_reports
                    WHERE node = ? AND report = ?"
                  node report-hash]))))
