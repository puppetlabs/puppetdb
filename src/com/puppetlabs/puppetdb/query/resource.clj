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
  (:require [cheshire.core :as json]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [limited-query-to-vec
                                    convert-result-arrays
                                    with-transacted-connection
                                    add-limit-clause]]
        [com.puppetlabs.puppetdb.query :only [resource-query->sql resource-operators-v1 resource-operators-v2]]
        [com.puppetlabs.puppetdb.query.utils :only [valid-query-format?]]))

(defn query->sql
  "Compile a resource `query` into an SQL expression using the specified set of
  `operators`."
  [operators query]
  {:pre  [(vector? query)]
   :post [(valid-query-format? %)]}
  (let [[subselect & params] (resource-query->sql operators query)
        sql (format (str "SELECT certname, resource, type, title, tags, exported, sourcefile, sourceline, rp.name, rp.value "
                         "FROM (%s) subquery1 "
                         "LEFT OUTER JOIN resource_params rp USING(resource)")
                    subselect)]
    (apply vector sql params)))

(def v1-query->sql
  (partial query->sql resource-operators-v1))

(def v2-query->sql
  (partial query->sql resource-operators-v2))

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
