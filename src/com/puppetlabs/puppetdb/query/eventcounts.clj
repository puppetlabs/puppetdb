(ns com.puppetlabs.puppetdb.query.eventcounts
  (:require [com.puppetlabs.puppetdb.query.event :as events]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [valid-jdbc-query? query-to-vec dashes->underscores]]
        [com.puppetlabs.puppetdb.query :only [compile-term]]
        [clojure.core.match :only [match]]))

(defn- compile-event-count-equality
  ;; TODO docs
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
  ;; TODO docs
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
  ;; TODO docs
  [summarize-by]
  (condp = summarize-by
    "certname" "certname"
    "containing-class" "containing_class"
    "resource" "resource_type, resource_title"
    (throw (IllegalArgumentException. (format "Unsupported value for 'summarize-by': '%s'" summarize-by)))))

(defn- get-counts-filter-where-clause
  ;; TODO docs
  [counts-filter]
  (if counts-filter
    (compile-term event-count-ops counts-filter)
    {:where nil :params []}))

(defn- get-count-by-sql
  ;; TODO docs
  [sql count-by group-by]
  (condp = count-by
    "resource"  sql
    "node"      (format "SELECT DISTINCT certname, status, %s FROM (%s) distinct_events" group-by sql)
    (throw (IllegalArgumentException. (format "Unsupported value for 'count-by': '%s'" count-by)))))

(defn- get-event-count-sql
  ;; TODO docs
  [event-sql group-by]
  (format "SELECT %s,
              SUM(CASE WHEN status = 'failure' THEN 1 ELSE 0 END) AS failures,
              SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successes,
              SUM(CASE WHEN status = 'noop' THEN 1 ELSE 0 END) AS noops,
              SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skips
            FROM (%s) events
            GROUP BY %s"
          group-by
          event-sql
          group-by))

(defn- get-filtered-sql
  ;; TODO docs
  [sql where]
  (if where
    (format "SELECT * FROM (%s) count_results WHERE %s" sql where)
    sql))

(defn query->sql
  ;; TODO docs
  [query summarize-by counts-filter count-by]
  {:pre  [(vector? query)
          ((some-fn nil? vector?) counts-filter)
          (string? summarize-by)
          (string? count-by)]
   :post [(valid-jdbc-query? %)]}
  (let [group-by                        (get-group-by summarize-by)
        {counts-filter-where  :where
         counts-filter-params :params}  (get-counts-filter-where-clause counts-filter)
        [event-sql & event-params]      (events/query->sql query)
        count-by-sql                    (get-count-by-sql event-sql count-by group-by)
        event-count-sql                 (get-event-count-sql count-by-sql group-by)
        filtered-sql                    (get-filtered-sql event-count-sql counts-filter-where)]
    (apply vector filtered-sql (concat event-params counts-filter-params))))

(defn query-event-counts
  ;; TODO docs
  [[sql & params]]
  {:pre [(string? sql)]}
  (query-to-vec (apply vector sql params)))
