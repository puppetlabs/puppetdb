;; ## SQL query compiler
;;
;; The query compiler operates in effectively a three-step process. Because the
;; query is compiled depth-first, however, the first two steps may be
;; intermingled.
;;
;; The first step is compilation of = predicates into where clauses, params,
;; and any necessary join tables. The where clauses are formulated such that
;; they can be combined using AND/OR without requiring any extra joins or
;; logic.
;;
;; The second step is compilation of and/or/not predicates. The first two of
;; these are compiled fairly trivially by joining their child WHERE clauses
;; with AND/OR, and concatenating the joins and params lists. "not" predicates
;; are first compiled as an OR predicate, whose WHERE clause is then prepended
;; with NOT.
;;
;; The final step is building the ultimate query which will be executed. This
;; means building JOIN expressions for any necessary tables, and appending the
;; JOINs and WHERE clause to the query which fetches the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resource
  (:require [com.puppetlabs.puppetdb.query.facts :as facts]
            [com.puppetlabs.utils :as utils]
            [cheshire.core :as json]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [limited-query-to-vec
                                    convert-result-arrays
                                    with-transacted-connection
                                    add-limit-clause]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-array-query-string]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.puppetdb.query.utils :only [valid-query-format?]]))

(defmulti compile-term
  "Recursively compile a query into a structured map reflecting the terms of
  the query."
  (fn [query]
    (let [operator (string/lower-case (first query))]
      (cond
       (#{"and" "or"} operator) :connective
       :else operator))))

(defn build-join-expr
  "Builds an inner join expression between catalog_resources and the given
  `table`. The only currently acceptable table is `certnames`,
  which *must* have rows corresponding to the `catalog_resources`
  table, ensuring the INNER JOIN won't lose any rows."
  [table]
  (condp = table
    ;; We will always also join to certname_catalogs if we're joining to
    ;; certnames, but handle it separately, because we could ONLY join to
    ;; certname_catalogs, and don't want duplicate joins. I wish this were more
    ;; generic.
    :certnames
    "INNER JOIN certnames ON certname_catalogs.certname = certnames.name"))

(defn query->sql
  "Compile a query into an SQL expression."
  [query]
  {:pre  [(vector? query)]
   :post [(valid-query-format? %)]}
  (let [{:keys [where joins params]} (compile-term query)
        join-expr                    (->> joins
                                          (map build-join-expr)
                                          (string/join " "))
        sql (format (str "SELECT certname_catalogs.certname, catalog_resources.resource, catalog_resources.type, catalog_resources.title,"
                         "catalog_resources.tags, catalog_resources.exported, catalog_resources.sourcefile, catalog_resources.sourceline, rp.name, rp.value "
                         "FROM catalog_resources "
                         "JOIN certname_catalogs USING(catalog) "
                         "LEFT OUTER JOIN resource_params rp "
                         "USING(resource) %s WHERE %s")
                    join-expr where)]
    (apply vector sql params)))

(defn limited-query-resources
  "Take a limit, a query, and its parameters, and return a vector of resources
   and their parameters which match.  Throws an exception if the query would
   return more than `limit` results.  (A value of `0` for `limit` means
   that the query should not be limited.)"
  [limit [query & params]]
  {:pre  [(and (integer? limit) (>= limit 0))]
   :post [(or (zero? limit) (<= (count %) limit))]}
  (let [limited-query (add-limit-clause limit query)
        results       (limited-query-to-vec limit (apply vector limited-query params))
        metadata_cols [:certname :resource :type :title :tags :exported :sourcefile :sourceline]
        metadata      (apply juxt metadata_cols)]
    (vec (for [[resource params] (group-by metadata results)]
           (assoc (zipmap metadata_cols resource) :parameters
                  (into {} (for [param params :when (:name param)]
                             [(:name param) (json/parse-string (:value param))])))))))

(defn query-resources
  "Take a query and its parameters, and return a vector of resources
   and their parameters which match."
  [[sql & params]]
  {:pre [(string? sql)]}
  (limited-query-resources 0 (apply vector sql params)))

;; Compile an '=' predicate, the basic atom of a resource query. This
;; will produce a query that selects a set of hashes matching the
;; predicate, which can then be combined with connectives to build
;; complex queries.
(defmethod compile-term "="
  [[op path value :as term]]
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (match [path]
         ;; tag join. Tags are case-insensitive but always lowercase, so
         ;; lowercase the query value.
         ["tag"]
         {:where  (sql-array-query-string "tags")
          :params [(string/lower-case value)]}

         ;; node join.
         [["node" "name"]]
         {:where  "certname_catalogs.certname = ?"
          :params [value]}

         ;; {in,}active nodes.
         [["node" "active"]]
         {:joins [:certnames]
          :where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

         ;; param joins.
         [["parameter" (name :when string?)]]
         {:where  "catalog_resources.resource IN (SELECT rp.resource FROM resource_params rp WHERE rp.name = ? AND rp.value = ?)"
          :params [name (db-serialize value)]}

         ;; metadata match.
         [(metadata :when #{"catalog" "resource" "type" "title" "tags" "exported" "sourcefile" "sourceline"})]
           {:where  (format "catalog_resources.%s = ?" metadata)
            :params [value]}

         ;; ...else, failure
         :else (throw (IllegalArgumentException.
                       (str term " is not a valid query term")))))

(defmethod compile-term "select-facts"
  [[_ subquery & others]]
  {:pre [(coll? subquery)]
   :post [(string? (:where %))]}
  (when-not (empty? others)
    (throw (IllegalArgumentException. "Only one expression is accepted for 'select-facts'")))
  (let [[subsql & params] (facts/query->sql subquery)]
    {:where (format "SELECT * FROM (%s) r1" subsql)
     :params params}))

(def fact-columns #{"certname" "node" "fact" "value"})

(def resource-columns #{"certname" "catalog" "resource" "type" "title" "tags" "exported" "sourcefile" "sourceline"})

(def selectable-columns
  {"select-resources" resource-columns
   "select-facts" fact-columns})

(defmethod compile-term "project"
  [[_ field subselect]]
  {:pre [(string? field)
         (coll? subselect)]
   :post [(map? %)
          (string? (:where %))]}
  (let [{:keys [where params] :as query} (compile-term subselect)
        select-type (first subselect)
        field-names (selectable-columns select-type)]
    (when-not (field-names field)
      (throw (IllegalArgumentException. (format "Can't project unknown field '%s' for '%s'" field select-type))))
    (assoc query :where (format "SELECT r1.%s FROM (%s) r1" field where))))

(defmethod compile-term "in-result"
  [[_ [type field] subselect]]
  {:pre [(string? type)
         (string? field)
         (coll? subselect)]
   :post [(map? %)
          (string? (:where %))]}
  (let [{:keys [where params] :as query} (compile-term subselect)]
    (when-not (resource-columns field)
      (throw (IllegalArgumentException. (format "Can't match on unknown %s field '%s' for 'in-result'" type field))))
    (assoc query :where (format "%s IN (%s)" field where))))

;; Join a set of predicates together with an 'and' relationship,
;; performing an intersection (via natural join).
(defmethod compile-term :connective
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms  (map compile-term terms)
        joins  (distinct (mapcat :joins terms))
        params (mapcat :params terms)
        query  (->> (map :where terms)
                    (map #(format "(%s)" %))
                    (string/join (format " %s " (string/upper-case op))))]
    {:joins  joins
     :where  query
     :params params}))

;; Join a set of predicates together with a 'not' relationship,
;; performing a set difference. This will reject resources matching
;; _any_ child predicate.
(defmethod compile-term "not"
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [term  (compile-term (cons "or" terms))
        query (format "NOT (%s)" (:where term))]
    (assoc term :where query)))
