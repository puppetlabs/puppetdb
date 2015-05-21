(ns puppetlabs.puppetdb.query.event-counts
  (:require [puppetlabs.puppetdb.query.events :as events]
            [clojure.string :as string]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query-eng.engine :as qe]))

(defn- validate-group-by!
  "Given the value to summarize by, return the appropriate database field to be used in the SQL query.
  Supported values are `certname`, `containing-class`, and `resource` (default), otherwise an
  IllegalArgumentException is thrown."
  [summarize_by]
  {:pre  [(string? summarize_by)]
   :post [(vector? %)]}
  (case summarize_by
    ("containing_class" "certname") [summarize_by]
    "resource" ["resource_type" "resource_title"]
    (throw (IllegalArgumentException. (format "Unsupported value for 'summarize_by': '%s'" summarize_by)))))

(defn- validate-count-by!
  "Validate a count_by expression, defaulting to `resource` if no count_by expression is supplied."
  [count_by]
  (case count_by
    nil "resource"
    ("resource" "certname") count_by
    (throw (IllegalArgumentException. (format "Unsupported value for 'count_by': '%s'" count_by)))))

(defn- get-count-by-sql
  "Given the events `sql`, a value to `count-by`, and a value to `group-by`,
  return the appropriate SQL string that counts and groups the `sql` results.
  Supported `count-by` values are `resource` (default) and `certname`, otherwise
  an IllegalArgumentException is thrown."
  [sql count-by group-by]
  {:pre  [(string? sql)
          (contains? #{"certname" "resource"} count-by)
          (vector? group-by)]
   :post [(string? %)]}
  (if (= count-by "certname")
    (str "SELECT DISTINCT certname, status"
         (when-not (= group-by ["certname"]) (str ", " (string/join ", " group-by)))
         " FROM (" sql ") distinct_events")
    sql))

(defn- event-counts-columns
  [group-by]
  {:pre [(vector? group-by)]}
  (concat ["failures" "successes" "noops" "skips"] group-by))

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
  [summarize_by raw-result]
  {:pre [(contains? #{"certname" "resource" "containing_class"} summarize_by)
         (map? raw-result)
         (or (contains? raw-result :certname)
             (and (contains? raw-result :resource_type)
                  (contains? raw-result :resource_title))
             (contains? raw-result :containing_class))]
   :post [(map? %)
          (not (kitchensink/contains-some % [:certname :resource_type :resource_title :containing_class]))
          (map? (:subject %))
          (= summarize_by (:subject_type %))]}
  (let [result (assoc raw-result :subject_type summarize_by)]
    (case summarize_by
      ("containing_class" "certname") (let [summarize-by-keyword (keyword summarize_by)]
                                        (-> (assoc result :subject {:title (summarize-by-keyword result)})
                                            (dissoc summarize-by-keyword)))
      "resource" (-> (assoc result :subject {:type (:resource_type result)
                                             :title (:resource_title result)})
                     (dissoc :resource_type :resource_title)))))

(defn munge-result-rows
  "Helper function to transform the event count subject data from the raw format that we get back from the
  database into the more structured format that the API specifies."
  [summarize_by]
  (fn [_ _] 
   (fn [rows]
     (map (partial munge-subject summarize_by) rows))))

(defn query->sql
  "Convert an event-counts `query` and a value to `summarize_by` into a SQL string.
  A second `counts-filter` query may be provided to further reduce the results, and
  the value to `count_by` may also be specified (defaults to `resource`)."
  ([version query [summarize_by
                   {:keys [counts_filter
                           count_by
                           distinct_resources?] :as query-options}
                   paging-options]]
     {:pre  [(sequential? query)
             (string? summarize_by)
             ((some-fn nil? sequential?) counts_filter)
             ((some-fn nil? string?) count_by)]
      :post [(map? %)
             (jdbc/valid-jdbc-query? (:results-query %))
             (or (not (:count? paging-options))
                 (jdbc/valid-jdbc-query? (:count-query %)))]}
     (let [group-by (validate-group-by! summarize_by)
           count-by (validate-count-by! count_by)]
       (paging/validate-order-by! (->> (event-counts-columns group-by)
                                       (map keyword))
                                  paging-options)
       (let [{counts-filter-where :where
              counts-filter-params :params} (some->> counts_filter
                                                     (query/compile-term query/event-count-ops)) 
             distinct-opts (-> query-options
                               (select-keys [:distinct_resources? :distinct_start_time :distinct_end_time])
                               vector)
             {:keys [results-query]} (if distinct_resources? ;;<- The query engine does not support distinct-resources!
                                       (events/query->sql version query distinct-opts)
                                       (qe/compile-user-query->sql qe/report-events-query query))
             update-sql #(update %1 0 %2)
             jdbc-query (-> results-query
                            (update-sql (fn [event-sql]
                                          (-> event-sql
                                              (get-count-by-sql count-by group-by)
                                              (get-event-count-sql group-by)
                                              (get-filtered-sql counts-filter-where))))
                            (concat counts-filter-params)
                            vec)]
         (cond-> {:results-query (update-sql jdbc-query #(jdbc/paged-sql % paging-options))}
           (:count? paging-options) (assoc :count-query (update-sql jdbc-query jdbc/count-sql)))))))

(defn query-event-counts
  "Given a SQL query and its parameters, return a vector of matching results."
  [version summarize_by query-sql]
  {:pre  [(map? query-sql)]}
  (let [{:keys [results-query count-query]} query-sql
        munge-fn ((munge-result-rows summarize_by) nil nil)]
    (cond-> {:result (->> (jdbc/with-query-results-cursor results-query)
                          munge-fn
                          (into []))}
      count-query (assoc :count (jdbc/get-result-count count-query)))))
