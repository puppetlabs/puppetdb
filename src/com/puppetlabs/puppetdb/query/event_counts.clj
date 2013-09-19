(ns com.puppetlabs.puppetdb.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.events :as events]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [valid-jdbc-query? dashes->underscores underscores->dashes]]
        [com.puppetlabs.puppetdb.query :only [compile-term execute-query]]
        [com.puppetlabs.puppetdb.query.paging :only [validate-order-by!]]
        [com.puppetlabs.utils :only [contains-some]]
        [clojure.core.match :only [match]]))

(defn- compile-event-count-equality
  "Compile an = predicate for event-count query.  The `path` represents
  the field to query against, and `value` is the value of the field."
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (let [db-field (dashes->underscores path)]
    (match [db-field]
      [(field :when #{"successes" "failures" "noops" "skips"})]
      {:where (format "%s = ?" field)
       :params [value]}

      :else (throw (IllegalArgumentException. (str path " is not a queryable object for event counts"))))))

(defn- compile-event-count-inequality
  "Compile an inequality for an event-counts query (> < >= <=).  The `path`
  represents the field to query against, and the `value` is the value of the field."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
    [(field :when #{"successes" "failures" "noops" "skips"})]
    {:where (format "%s %s ?" field op)
     :params [value]}

    :else (throw (IllegalArgumentException. (format "%s operator does not support object '%s' for event counts" op path)))))

(defn- event-count-ops
  "Maps resource event count operators to the functions implementing them.
  Returns nil if the operator is unknown."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= "=" op) compile-event-count-equality
      (#{">" "<" ">=" "<="} op) (partial compile-event-count-inequality op))))

(defn- get-group-by
  "Given the value to summarize by, return the appropriate database field to be used in the SQL query.
  Supported values are `certname`, `containing-class`, and `resource` (default), otherwise an
  IllegalArgumentException is thrown."
  [summarize-by]
  {:pre  [(string? summarize-by)]
   :post [(vector? %)]}
  (condp = summarize-by
    "certname" ["certname"]
    "containing-class" ["containing_class"]
    "resource" ["resource_type" "resource_title"]
    (throw (IllegalArgumentException. (format "Unsupported value for 'summarize-by': '%s'" summarize-by)))))

(defn- get-counts-filter-where-clause
  "Given a `counts-filter` query, return the appropriate SQL where clause and parameters.
  Returns a noop map if the `counts-filter` is nil."
  [counts-filter]
  {:pre  [((some-fn nil? sequential?) counts-filter)]
   :post [(map? %)]}
  (if counts-filter
    (compile-term event-count-ops counts-filter)
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
    (throw (IllegalArgumentException. (format "Unsupported value for 'count-by': '%s'" count-by)))))

(defn- event-counts-columns
  [group-by]
  {:pre [(vector? group-by)]}
  (concat
    ["failures" "successes" "noops" "skips"]
    (map underscores->dashes group-by)))

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
  [summarize-by result]
  {:pre [(contains? #{"certname" "resource" "containing-class"} summarize-by)
         (map? result)
         (or
           (contains? result :certname)
           (every? #(contains? result %) [:resource_type :resource_title])
           (contains? result :containing_class))]
   :post [(map? %)
          (not (contains-some % [:certname :resource_type :resource_title :containing_class]))
          (map? (:subject %))
          (= summarize-by (:subject-type %))]}
  (condp = summarize-by
    "certname"          (-> result
                          (assoc :subject-type "certname")
                          (assoc :subject {:title (:certname result)})
                          (dissoc :certname))

    "resource"          (-> result
                          (assoc :subject-type "resource")
                          (assoc :subject {:type (:resource_type result) :title (:resource_title result)})
                          (dissoc :resource_type :resource_title))

    "containing-class"  (-> result
                          (assoc :subject-type "containing-class")
                          (assoc :subject {:title (:containing_class result)})
                          (dissoc :containing_class))))

(defn- munge-subjects
  "Helper function to transform the event count subject data from the raw format that we get back from the
  database into the more structured format that the API specifies."
  [summarize-by results]
  {:pre [(vector? results)]
   :post [(vector? %)]}
  (mapv (partial munge-subject summarize-by) results))

(defn query->sql
  "Convert an event-counts `query` and a value to `summarize-by` into a SQL string.
  A second `counts-filter` query may be provided to further reduce the results, and
  the value to `count-by` may also be specified (defaults to `resource`)."
  ([query summarize-by]
    (query->sql query summarize-by {}))
  ([query summarize-by {:keys [counts-filter count-by] :as query-options}]
    {:pre  [(sequential? query)
            (string? summarize-by)
            ((some-fn nil? sequential?) counts-filter)
            ((some-fn nil? string?) count-by)]
     :post [(valid-jdbc-query? %)]}
    (let [count-by                        (or count-by "resource")
          group-by                        (get-group-by summarize-by)
          {counts-filter-where  :where
           counts-filter-params :params}  (get-counts-filter-where-clause counts-filter)
          [event-sql & event-params]      (events/query->sql
                                            (select-keys query-options [:distinct-resources?])
                                            query)
          count-by-sql                    (get-count-by-sql event-sql count-by group-by)
          event-count-sql                 (get-event-count-sql count-by-sql group-by)
          filtered-sql                    (get-filtered-sql event-count-sql counts-filter-where)]
      (apply vector filtered-sql (concat event-params counts-filter-params)))))

(defn query-event-counts
  "Given a SQL query and its parameters, return a vector of matching results."
  ([sql-and-params summarize-by]
    (query-event-counts {} summarize-by sql-and-params))
  ([paging-options summarize-by [sql & params]]
   {:pre  [(string? sql)]
    :post [(map? %)
           (vector? (:result %))]}
    (let [group-by (get-group-by summarize-by)]
      (validate-order-by! (event-counts-columns group-by) paging-options))
    (-> (execute-query (apply vector sql params) paging-options)
      (update-in [:result] (partial munge-subjects summarize-by)))))
