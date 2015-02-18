(ns puppetlabs.puppetdb.query.event-counts
  (:require [puppetlabs.puppetdb.query.events :as events]
            [clojure.string :as string]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query-eng.engine :as qe]))

(defn- get-group-by
  "Given the value to summarize by, return the appropriate database field to be used in the SQL query.
  Supported values are `certname`, `containing-class`, and `resource` (default), otherwise an
  IllegalArgumentException is thrown."
  [summarize_by]
  {:pre  [(string? summarize_by)]
   :post [(vector? %)]}
  (condp = summarize_by
    "certname" ["certname"]
    "containing_class" ["containing_class"]
    "resource" ["resource_type" "resource_title"]
    (throw (IllegalArgumentException. (format "Unsupported value for 'summarize_by': '%s'" summarize_by)))))

(defn- get-counts-filter-where-clause
  "Given a `counts-filter` query, return the appropriate SQL where clause and parameters.
  Returns a noop map if the `counts-filter` is nil."
  [counts-filter]
  {:pre  [((some-fn nil? sequential?) counts-filter)]
   :post [(map? %)]}
  (if counts-filter
    (query/compile-term query/event-count-ops counts-filter)
    {:where nil :params []}))

(defn- get-count-by-sql
  "Given the events `sql`, a value to `count-by`, and a value to `group-by`,
  return the appropriate SQL string that counts and groups the `sql` results.
  Supported `count-by` values are `resource` (default) and `certname`, otherwise
  an IllegalArgumentException is thrown."
  [sql count-by group-by]
  {:pre  [(string? sql)
          (string? count-by)
          (vector? group-by)]
   :post [(string? %)]}
  (condp = count-by
    "resource"  sql
    "certname"  (let [field-string (if (= group-by ["certname"]) "" (str ", " (string/join ", " group-by)))]
                  (format "SELECT DISTINCT certname, status%s FROM (%s) distinct_events" field-string sql))
    (throw (IllegalArgumentException. (format "Unsupported value for 'count_by': '%s'" count-by)))))

(defn- event-counts-columns
  [group-by]
  {:pre [(vector? group-by)]}
  (concat
   ["failures" "successes" "noops" "skips"]
   group-by))

(defn- get-event-count-sql
  "Given the `event-sql` and value to `group-by`, return a SQL string that
  will sum the results of `event-sql` grouped by the provided value."
  [event-sql group-by]
  {:pre  [(string? event-sql)
          (vector? group-by)]
   :post [(string? %)]}
  (format "SELECT %s,
              SUM(CASE WHEN status = 'failure' THEN 1 ELSE 0 END) AS failures,
              SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successes,
              SUM(CASE WHEN status = 'noop' THEN 1 ELSE 0 END) AS noops,
              SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skips
            FROM (%s) events
            GROUP BY %s"
          (string/join ", " group-by)
          event-sql
          (string/join ", " group-by)))

(defn- get-filtered-sql
  "Given a `sql` string and optional `where` clause, return the appropriate filtered
  SQL string.  If the `where` clause is nil, `sql` is returned."
  [sql where]
  {:pre  [(string? sql)
          ((some-fn nil? string?) where)]
   :post [(string? %)]}
  (if where
    (format "SELECT * FROM (%s) count_results WHERE %s" sql where)
    sql))

(defn- munge-subject
  "Helper function to transform the event count subject data from the raw format that we get back from the
  database into the more structured format that the API specifies."
  [summarize_by result]
  {:pre [(contains? #{"certname" "resource" "containing_class"} summarize_by)
         (map? result)
         (or
          (contains? result :certname)
          (every? #(contains? result %) [:resource_type :resource_title])
          (contains? result :containing_class))]
   :post [(map? %)
          (not (kitchensink/contains-some % [:certname :resource_type :resource_title :containing_class]))
          (map? (:subject %))
          (= summarize_by (:subject_type %))]}
  (condp = summarize_by
    "certname"          (-> result
                            (assoc :subject_type "certname")
                            (assoc :subject {:title (:certname result)})
                            (dissoc :certname))

    "resource"          (-> result
                            (assoc :subject_type "resource")
                            (assoc :subject {:type (:resource_type result) :title (:resource_title result)})
                            (dissoc :resource_type :resource_title))

    "containing_class"  (-> result
                            (assoc :subject_type "containing_class")
                            (assoc :subject {:title (:containing_class result)})
                            (dissoc :containing_class))))

(defn munge-result-rows
  "Helper function to transform the event count subject data from the raw format that we get back from the
  database into the more structured format that the API specifies."
  [_ _ paging-options]
  (let [summarize_by (first paging-options)]
    (fn [rows]
      (mapv
       (partial munge-subject summarize_by)
       rows))))

(defn query->sql
  "Convert an event-counts `query` and a value to `summarize_by` into a SQL string.
  A second `counts-filter` query may be provided to further reduce the results, and
  the value to `count_by` may also be specified (defaults to `resource`)."
  ([version query [summarize_by {:keys [counts_filter count_by] :as query-options} paging-options]]
     {:pre  [(sequential? query)
             (string? summarize_by)
             ((some-fn nil? sequential?) counts_filter)
             ((some-fn nil? string?) count_by)]
      :post [(map? %)
             (jdbc/valid-jdbc-query? (:results-query %))
             (or
              (not (:count? paging-options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}
     (let [count_by                        (or count_by "resource")
           group-by                        (get-group-by summarize_by)
           _                               (paging/validate-order-by!
                                            (map keyword (event-counts-columns group-by))
                                            paging-options)
           {counts-filter-where  :where
            counts-filter-params :params}  (get-counts-filter-where-clause counts_filter)
           distinct-opts                   (select-keys query-options
                                                        [:distinct_resources? :distinct_start_time :distinct_end_time])
           [event-sql & event-params]      (:results-query
                                            (if (:distinct_resources? query-options) ;;<- The query engine does not support distinct-resources!
                                              (events/query->sql version query [distinct-opts nil])
                                              (qe/compile-user-query->sql qe/report-events-query query)))
           count-by-sql                    (get-count-by-sql event-sql count_by group-by)
           event-count-sql                 (get-event-count-sql count-by-sql group-by)
           sql                             (get-filtered-sql event-count-sql counts-filter-where)
           params                          (concat event-params counts-filter-params)
           paged-select                    (jdbc/paged-sql sql paging-options)]
       (conj {:results-query (apply vector paged-select params)}
             (when (:count? paging-options)
               [:count-query (apply vector (jdbc/count-sql sql) params)])))))

(defn query-event-counts
  "Given a SQL query and its parameters, return a vector of matching results."
  [version summarize_by query-sql]
  {:pre  [(map? query-sql)]}
  (let [{[sql & params] :results-query
         count-query    :count-query} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                          (comp doall (munge-result-rows summarize_by)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))
