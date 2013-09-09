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
                                    valid-jdbc-query?
                                    get-result-count
                                    paged-sql
                                    count-sql]]
        [com.puppetlabs.puppetdb.query :only [resource-query->sql
                                              resource-operators-v1
                                              resource-operators-v2
                                              resource-columns]]
        [com.puppetlabs.puppetdb.http.paging :only [validate-order-by!]]))

(defn query->sql
  "Compile a resource `query` into an SQL expression using the specified set of
  `operators`."
  ([operators query] (query->sql operators query {}))
  ([operators query paging-options]
   {:pre  [(sequential? query)]
    :post [(map? %)
           (valid-jdbc-query? (:results-query %))
           (or
             (not (:count? paging-options))
             (valid-jdbc-query? (:count-query %)))]}
    (let [columns (map keyword (keys resource-columns))]
      (validate-order-by! columns paging-options))
    (let [[subselect & params] (resource-query->sql operators query)
          paged-subselect      (paged-sql subselect paging-options)
          sql (format (str "SELECT subquery1.certname, subquery1.resource, "
                                  "subquery1.type, subquery1.title, subquery1.tags, "
                                  "subquery1.exported, subquery1.sourcefile, "
                                  "subquery1.sourceline, rp.name, rp.value "
                            "FROM (%s) subquery1 "
                            "LEFT OUTER JOIN resource_params rp "
                                "ON rp.resource = subquery1.resource")
                paged-subselect)
          ;; This is a little more complex than I'd prefer; the general query paging
          ;;  functions are built to work for SQL queries that return 1 row per
          ;;  PuppetDB result.  Since that's not the case for resources right now,
          ;;  we have to actually manage two separate SQL queries.  The introduction
          ;;  of the new `resource-params-cache` table in the next release should
          ;;  alleviate this problem and allow us to simplify this code.
          result               {:results-query (apply vector sql params)}]
      (if (:count? paging-options)
        (assoc result :count-query (apply vector (count-sql subselect) params))
        result))))

(def v1-query->sql
  (partial query->sql resource-operators-v1))

(def v2-query->sql
  (partial query->sql resource-operators-v2))

(defn limited-query-resources
  "Take a limit, a query, and its parameters, and return a vector of resources
   and their parameters which match.  Throws an exception if the query would
   return more than `limit` results.  (A value of `0` for `limit` means
   that the query should not be limited.)"
  [limit {:keys [results-query count-query] :as secondarg}]
  {:pre  [(and (integer? limit) (>= limit 0))]
   :post [(or (zero? limit) (<= (count %) limit))]}
  (let [[query & params] results-query
        limited-query (add-limit-clause limit query)
        results       (limited-query-to-vec limit (apply vector limited-query params))
        metadata_cols [:certname :resource :type :title :tags :exported :sourcefile :sourceline]
        metadata      (apply juxt metadata_cols)
        results       {:results
                        (vec
                          (for [[resource params] (group-by metadata results)]
                             (assoc (zipmap metadata_cols resource) :parameters
                               (into {} (for [param params :when (:name param)]
                                          [(:name param) (json/parse-string (:value param))])))))}]
    (if count-query
      (assoc results :count (get-result-count count-query))
      results)))

(defn query-resources
  "Take a query and its parameters, and return a vector of resources
   and their parameters which match."
  [queries-map]
  {:pre [(map? queries-map)
         (valid-jdbc-query? (:results-query queries-map))]}
    (limited-query-resources 0 queries-map))
