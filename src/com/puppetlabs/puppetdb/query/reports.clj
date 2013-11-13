;; ## SQL/query-related functions for reports

(ns com.puppetlabs.puppetdb.query.reports
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [query-to-vec underscores->dashes valid-jdbc-query?]]
        [com.puppetlabs.puppetdb.query :only [execute-query compile-term]]
        [com.puppetlabs.puppetdb.query.events :only [events-for-report-hash]]
        [com.puppetlabs.puppetdb.query.paging :only [validate-order-by!]]))

;; ## Report query functions
;;
;; The following functions provide the basic logic for composing and executing
;; queries against reports (report summaries / metadata).

(defn compile-equals-term
  "Compile a report query into a structured map reflecting the terms
   of the query. Currnetly only the `=` operator is supported"
  [& [path value :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [num-args (count term)]
    (when-not (= 2 num-args)
      (throw (IllegalArgumentException.
              (format "= requires exactly two arguments, but we found %d" num-args)))))
  (case path
    "certname" {:where "reports.certname = ?"
                :params [value] }
    "hash"     {:where "reports.hash = ?"
                :params [value]}
    (throw (IllegalArgumentException.
            (format "'%s' is not a valid query term" path)))))

(def report-term-map {"=" compile-equals-term})

(defn report-query->sql
  "Compile a report query into an SQL expression."
  [query]
  {:pre [(sequential? query)]
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-term report-term-map query)]
    (apply vector (format " WHERE %s" where) params)))

(def report-columns
  ["hash"
   "certname"
   "puppet_version"
   "report_format"
   "configuration_version"
   "start_time"
   "end_time"
   "receive_time"
   "transaction_uuid"])

(defn query-reports
  "Take a query and its parameters, and return a vector of matching reports."
  ([sql-and-params] (query-reports {} sql-and-params))
  ([paging-options [sql & params]]
    {:pre [(string? sql)]}
    (validate-order-by! (map keyword report-columns) paging-options)
    (let [query   (format "SELECT %s FROM reports %s ORDER BY start_time DESC"
                    (string/join ", " report-columns)
                    sql)
          results (execute-query
                    (apply vector query params)
                    paging-options)]
      (update-in results [:result]
        (fn [rs] (map #(kitchensink/mapkeys underscores->dashes %) rs))))))

(defn reports-for-node
  "Return reports for a particular node."
  [node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (seq? %))]}
  (let [query ["=" "certname" node]
        reports (-> query
                  (report-query->sql)
                  (query-reports)
                  ;; We don't support paging in this code path, so we
                  ;; can just pull the results out of the return value
                  (:result))]
    (map
      #(merge % {:resource-events (events-for-report-hash (get % :hash))})
      reports)))

(defn report-for-hash
  "Convenience function; given a report hash, return the corresponding report object
  (without events)."
  [hash]
  {:pre  [(string? hash)]
   :post [(or (nil? %)
            (map? %))]}
  (let [query ["=" "hash" hash]]
    (-> query
      (report-query->sql)
      (query-reports)
      ;; We don't support paging in this code path, so we
      ;; can just pull the results out of the return value
      (:result)
      (first))))

(defn is-latest-report?
  "Given a node and a report hash, return `true` if the report is the most recent one for the node,
  and `false` otherwise."
  [node report-hash]
  {:pre  [(string? node)
          (string? report-hash)]
   :post [(kitchensink/boolean? %)]}
  (= 1 (count (query-to-vec
                ["SELECT report FROM latest_reports
                    WHERE certname = ? AND report = ?"
                  node report-hash]))))
