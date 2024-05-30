(ns puppetlabs.puppetdb.query.event-counts
  (:require
   [clojure.string :as string]
   [puppetlabs.i18n.core :refer [tru]]
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.query :as query]
   [puppetlabs.puppetdb.query-eng.engine :as qe]
   [puppetlabs.puppetdb.query.events :as events]
   [puppetlabs.puppetdb.query.paging :as paging]
   [puppetlabs.puppetdb.scf.storage :refer [store-corrective-change?]]))

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
    (throw (IllegalArgumentException. (tru "Unsupported value for ''summarize_by'': ''{0}''" summarize_by)))))

(def ^:private fields-basic ["failures" "skips" "successes" "noops"])
(def ^:private fields-with-correctives ["failures" "skips" "intentional_successes" "corrective_successes" "intentional_noops" "corrective_noops"])
(defn- fields
  []
  (if @store-corrective-change? fields-with-correctives
                                fields-basic))

(defn- get-counts-filter-where-clause
  "Given a `counts-filter` query, return the appropriate SQL where clause and parameters.
  Returns a noop map if the `counts-filter` is nil."
  [counts-filter]
  {:pre  [((some-fn nil? sequential?) counts-filter)]
   :post [(map? %)]}
  (if counts-filter
    (query/compile-term (partial query/event-count-ops (set (fields)))
                        counts-filter)
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
                  (format "SELECT DISTINCT certname, status, corrective_change%s FROM (%s) distinct_events" field-string sql))
    (throw (IllegalArgumentException. (tru "Unsupported value for ''count_by'': ''{0}''" count-by)))))

(defn- event-counts-columns
  [group-by]
  {:pre [(vector? group-by)]}
  (concat (fields)
          group-by))

(defn- get-event-count-sql
  "Given the `event-sql` and value to `group-by`, return a SQL string that
  will sum the results of `event-sql` grouped by the provided value."
  [event-sql group-by]
  {:pre  [(string? event-sql)
          (vector? group-by)]
   :post [(string? %)]}
  (format (str "SELECT %s,
                       SUM(CASE WHEN status = 'failure' THEN 1 ELSE 0 END) AS failures,
                       SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skips, "
               (if @store-corrective-change?
                 "     SUM(CASE WHEN (status = 'success' and coalesce(corrective_change, false) = false) THEN 1 ELSE 0 END) AS intentional_successes,
                       SUM(CASE WHEN (status = 'success' and corrective_change = true) THEN 1 ELSE 0 END) AS corrective_successes,
                       SUM(CASE WHEN (status = 'noop' and coalesce(corrective_change, false) = false) THEN 1 ELSE 0 END) AS intentional_noops,
                       SUM(CASE WHEN (status = 'noop' and corrective_change = true) THEN 1 ELSE 0 END) AS corrective_noops "
                 "     SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successes,
                       SUM(CASE WHEN status = 'noop' THEN 1 ELSE 0 END) AS noops ")
               "  FROM (%s) events
                  GROUP BY %s")
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
  [summarize_by _ _]
  (fn [rows]
    (map (partial munge-subject summarize_by) rows)))

(defn query->sql
  "Convert an event-counts `query` and a value to `summarize_by` into a SQL string.
  A second `counts-filter` query may be provided to further reduce the results, and
  the value to `count_by` may also be specified (defaults to `resource`)."
  ([version query query-options]
   (query->sql false version query query-options))
  ([will-union?
    version
    query
    {:keys [summarize_by counts_filter count_by] :as query-options}]
     {:pre  [((some-fn nil? sequential?) query)
             (string? summarize_by)
             ((some-fn nil? sequential?) counts_filter)
             ((some-fn nil? string?) count_by)]
      :post [(map? %)
             (jdbc/valid-jdbc-query? (:results-query %))
             (or
              (not (:include_total query-options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}
     (let [count_by                        (or count_by "resource")
           group-by                        (get-group-by summarize_by)
           _                               (paging/validate-order-by!
                                            (map keyword (event-counts-columns group-by))
                                            query-options)
           {counts-filter-where  :where
            counts-filter-params :params}  (get-counts-filter-where-clause counts_filter)
           distinct-opts                   (select-keys query-options
                                                        [:distinct_resources
                                                         :distinct_start_time
                                                         :distinct_end_time])
           [event-sql & event-params]      (:results-query
                                            (if (:distinct_resources query-options)
                                              ;;The query engine does not support distinct-resources!
                                              (events/query->sql will-union? version query distinct-opts)
                                              (qe/compile-user-query->sql qe/report-events-query query)))
           count-by-sql                    (get-count-by-sql event-sql count_by group-by)
           event-count-sql                 (get-event-count-sql count-by-sql group-by)
           sql                             (get-filtered-sql event-count-sql counts-filter-where)
           params                          (concat event-params counts-filter-params)
           paged-select                    (jdbc/paged-sql sql query-options)]
       (conj {:results-query (apply vector paged-select params)}
             (when (:include_total query-options)
               [:count-query (apply vector (jdbc/count-sql sql) params)])))))
