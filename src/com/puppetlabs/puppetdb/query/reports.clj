(ns com.puppetlabs.puppetdb.query.reports
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as string]
            [com.puppetlabs.puppetdb.http :refer [remove-status v4?]]
            [clojure.core.match :refer [match]]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.events :refer [events-for-report-hash]]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

(def report-columns
  [:hash
   :certname
   :puppet-version
   :report-format
   :configuration-version
   :start-time
   :end-time
   :receive-time
   :transaction-uuid
   :environment
   :status])

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  ([version query]
   (query->sql version query {}))
  ([version query paging-options]
   {:pre  [((some-fn nil? sequential?) query)]
    :post [(map? %)
           (jdbc/valid-jdbc-query? (:results-query %))
           (or
            (not (:count? paging-options))
            (jdbc/valid-jdbc-query? (:count-query %)))]}
   (paging/validate-order-by! report-columns paging-options)
   (case version
     :v3
     (let [operators (query/report-ops version)
           [sql & params] (query/report-query->sql version operators query)
           paged-select (jdbc/paged-sql sql paging-options)
           result {:results-query (apply vector paged-select params)}]
       (if (:count? paging-options)
         (assoc result :count-query (apply vector (jdbc/count-sql sql) params))
         result))

     (qe/compile-user-query->sql
      qe/reports-query query paging-options))))

(defn munge-result-rows
  "Munge the result rows so that they will be compatible with the version
  specified API specification"
  [version]
  (fn [rows] (map (comp #(kitchensink/mapkeys jdbc/underscores->dashes %)
                        #(query/remove-environment % version)
                        #(remove-status % version))
                  rows)))

(defn query-reports
  "Queries reports and unstreams, used mainly for testing.

  This wraps the existing streaming query code but returns results
  and count (if supplied)."
  [version query-sql]
  {:pre [(map? query-sql)]}
  (let [{[sql & params] :results-query
         count-query    :count-query} query-sql
        result {:result (query/streamed-query-result
                         version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                         (comp doall (munge-result-rows version)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn reports-for-node
  "Return reports for a particular node."
  [version node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (seq? %))]}
  (let [query ["=" "certname" node]
        reports (->> (query->sql version query)
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
    (->> (query->sql version query)
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
  (= 1 (count (jdbc/query-to-vec
               ["SELECT report FROM latest_reports
                    WHERE certname = ? AND report = ?"
                node report-hash]))))
