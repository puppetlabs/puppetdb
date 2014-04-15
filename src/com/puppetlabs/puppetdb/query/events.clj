;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.events
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as string]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.http :refer [remove-environment v4?]]
            [com.puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize sql-regexp-match]]
            [com.puppetlabs.puppetdb.query :refer [execute-query event-columns compile-term resource-event-ops]]
            [com.puppetlabs.puppetdb.query.paging :refer [validate-order-by!]]))

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

(defn query->sql
  "Compile a resource event `query` into an SQL expression."
  [version query-options query]
  {:pre  [(sequential? query)
          (let [distinct-options [:distinct-resources? :distinct-start-time :distinct-end-time]]
            (or (not-any? #(contains? query-options %) distinct-options)
                (every? #(contains? query-options %) distinct-options)))]
   :post [(jdbc/valid-jdbc-query? %)]}
  (let [{:keys [where params]}  (compile-term (resource-event-ops version) query)
        select-fields           (string/join ", "
                                   (map
                                     (fn [[column [table alias]]]
                                       (str table "." column
                                            (if alias (format " AS %s" alias) "")))
                                     event-columns))
        [sql params]            (if (:distinct-resources? query-options)
                                  (distinct-select select-fields where params
                                    (:distinct-start-time query-options)
                                    (:distinct-end-time query-options))
                                  (default-select select-fields where params))]
    (apply vector sql params)))

(defn limited-query-resource-events
  "Take a limit, paging-options map, a query, and its parameters,
  and return a map containing the results and metadata.

  The returned map will contain a key `:result`, whose value is vector of
  resource events which match the query.  If the paging-options indicate
  that a total result count should also be returned, then the map will
  contain an additional key `:count`, whose value is an integer.

  Throws an exception if the query would return more than `limit` results.
  (A value of `0` for `limit` means that the query should not be limited.)"
  [version limit paging-options [query & params]]
  {:pre  [(and (integer? limit) (>= limit 0))]
   :post [(or (zero? limit) (<= (count %) limit))
          (map? %)
          (contains? % :result)
          (sequential? (:result %))]}
  (validate-order-by! (map keyword (keys event-columns)) paging-options)
  (let [limited-query   (jdbc/add-limit-clause limit query)
        results         (execute-query
                          limit
                          (apply vector limited-query params)
                          paging-options)]
    (assoc results :result
      (map
        #(-> (kitchensink/mapkeys jdbc/underscores->dashes %)
             (update-in [:old-value] json/parse-string)
             (update-in [:new-value] json/parse-string)
             (remove-environment version))
        (:result results)))))

(defn query-resource-events
  "Take a paging-options map, a query, and its parameters, and return a map
  containing matching resource events and metadata.  For more information about
  the return value, see `limited-query-resource-events`."
  [version paging-options [sql & params]]
  {:pre [(string? sql)]}
  (limited-query-resource-events version 0 paging-options (apply vector sql params)))

(defn events-for-report-hash
  "Given a particular report hash, this function returns all events for that
   given hash."
  [version report-hash]
  {:pre [(string? report-hash)]
   :post [(vector? %)]}
  (let [query          ["=" "report" report-hash]
        ;; we aren't actually supporting paging through this code path for now
        paging-options {}]
    (->> query
         (query->sql version nil)
         (query-resource-events version paging-options)
         :result
         (mapv #(dissoc %
                        :run-start-time
                        :run-end-time
                        :report-receive-time
                        :environment)))))
