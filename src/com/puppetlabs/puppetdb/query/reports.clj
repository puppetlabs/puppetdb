;; ## SQL/query-related functions for reports

(ns com.puppetlabs.puppetdb.query.reports
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as string]
            [com.puppetlabs.puppetdb.http :refer [remove-status v4?]]
            [clojure.core.match :refer [match]]
            [com.puppetlabs.jdbc :refer [query-to-vec underscores->dashes valid-jdbc-query?]]
            [com.puppetlabs.puppetdb.query :refer [execute-query compile-term
                                                   compile-and remove-environment]]
            [com.puppetlabs.puppetdb.query.events :refer [events-for-report-hash]]
            [com.puppetlabs.puppetdb.query.paging :refer [validate-order-by!]]))

;; ## Report query functions
;;
;; The following functions provide the basic logic for composing and executing
;; queries against reports (report summaries / metadata).

(defn compile-equals-term
  "Compile a report query into a structured map reflecting the terms
   of the query. Currently only the `=` operator is supported"
  [version]
  (fn [& [path value :as term]]
    {:post [(map? %)
            (string? (:where %))]}
    (let [num-args (count term)]
      (when-not (= 2 num-args)
        (throw (IllegalArgumentException.
                (format "= requires exactly two arguments, but we found %d" num-args)))))
    (match [path]
           ["certname"]
           {:where "reports.certname = ?"
            :params [value] }

           ["hash"]
           {:where "reports.hash = ?"
            :params [value]}

           ["environment" :guard (v4? version)]
           {:where "environments.name = ?"
            :params [value]}

           ["status" :guard (v4? version)]
           {:where "report_statuses.status = ?"
            :params [value]}

           :else
           (throw (IllegalArgumentException.
                   (format "'%s' is not a valid query term for version %s of the reports API" path (last (name version))))))))

(defn report-terms
  [version]
  {"=" (compile-equals-term version)
   "and" (fn [& args]
           (apply compile-and (report-terms version) args))})

(defn report-query->sql
  "Compile a report query into an SQL expression."
  [version query]
  {:pre [(sequential? query)]
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-term (report-terms version) query)]
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
   "transaction_uuid"
   "environments.name as environment"
   "report_statuses.status as status"])

(defn query-reports
  "Take a query and its parameters, and return a vector of matching reports."
  ([version sql-and-params] (query-reports version {} sql-and-params))
  ([version paging-options [sql & params]]
     {:pre [(string? sql)]}
     (validate-order-by! (map keyword report-columns) paging-options)
     (let [query   (format "SELECT %s
                            FROM reports
                                 LEFT OUTER JOIN environments on reports.environment_id = environments.id
                                 LEFT OUTER JOIN report_statuses on reports.status_id = report_statuses.id
                            %s ORDER BY start_time DESC"
                           (string/join ", " report-columns)
                           sql)
           results (execute-query
                    (apply vector query params)
                    paging-options)]
       (update-in results [:result]
                  (fn [rs] (map (comp #(kitchensink/mapkeys underscores->dashes %)
                                     #(remove-environment % version)
                                     #(remove-status % version)) rs))))))

(defn reports-for-node
  "Return reports for a particular node."
  [version node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (seq? %))]}
  (let [query ["=" "certname" node]
        reports (->> query
                     (report-query->sql version)
                     (query-reports version)
                     ;; We don't support paging in this code path, so we
                     ;; can just pull the results out of the return value
                     :result)]
    (map
     #(merge % {:resource-events (events-for-report-hash version (get % :hash))})
     reports)))

(defn report-for-hash
  "Convenience function; given a report hash, return the corresponding report object
  (without events)."
  [version hash]
  {:pre  [(string? hash)]
   :post [(or (nil? %)
              (map? %))]}
  (let [query ["=" "hash" hash]]
    (->> query
         (report-query->sql version)
         (query-reports version)
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
