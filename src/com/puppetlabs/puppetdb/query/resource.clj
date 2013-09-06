;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resource
  (:require [cheshire.core :as json]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [limited-query-to-vec
                                    convert-result-arrays
                                    with-transacted-connection
                                    add-limit-clause
                                    valid-jdbc-query?]]
        [com.puppetlabs.puppetdb.query :only [resource-query->sql resource-operators-v1 resource-operators-v2]]))

(defn query->sql
  "Compile a resource `query` into an SQL expression using the specified set of
  `operators`."
  ([operators query] (query->sql operators query {}))
  ([operators query paging-options]
   {:pre  [(sequential? query)]
    :post [(valid-jdbc-query? %)]}
    (let [[subselect & params] (resource-query->sql operators query paging-options)
          sql (format (str "SELECT subquery1.certname, subquery1.resource, "
                                  "subquery1.type, subquery1.title, subquery1.tags, "
                                  "subquery1.exported, subquery1.sourcefile, "
                                  "subquery1.sourceline, rp.name, rp.value "
                            "FROM (%s) subquery1 "
                            "LEFT OUTER JOIN resource_params rp "
                                "ON rp.resource = subquery1.resource")
                subselect)]
      (apply vector sql params))))

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
