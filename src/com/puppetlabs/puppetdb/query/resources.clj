;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resources
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [com.puppetlabs.utils :as utils])
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
                                              resource-operators-v3
                                              resource-columns]]
        [com.puppetlabs.puppetdb.query.paging :only [validate-order-by!]]))

(defn query->sql
  "Compile a resource `query` and an optional `paging-options` map, using the
  specified set of `operators`, into the SQL expressions necessary to retrieve
  the required data.

  The return value is a map.  It contains a key `:results-query`, which contains
  the SQL needed to retrieve the matching resources.  If the `paging-options`
  indicate that the user would also like a total count of available results,
  then the return value will also contain a key `:count-query` whose value
  contains the SQL necessary to retrieve the count data."
  ([operators query] (query->sql operators query {}))
  ([operators query paging-options]
   {:pre  [(sequential? query)]
    :post [(map? %)
           (valid-jdbc-query? (:results-query %))
           (or
             (not (:count? paging-options))
             (valid-jdbc-query? (:count-query %)))]}
    (validate-order-by! (keys resource-columns) paging-options)
    (let [[subselect & params] (resource-query->sql operators query)
          paged-subselect      (paged-sql subselect paging-options)
          sql                  (format "SELECT subquery1.certname, subquery1.resource,
                                               subquery1.type, subquery1.title, subquery1.tags,
                                               subquery1.exported, subquery1.file,
                                               subquery1.line, rp.name, rp.value
                                        FROM (%s) subquery1
                                        LEFT OUTER JOIN resource_params rp ON rp.resource = subquery1.resource"
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

(def v3-query->sql
  (partial query->sql resource-operators-v3))

(defn- consolidate-resource-params
  "Given the raw query results from the database, consolidate all the resource
  parameters for each resource and put them into a hash on each resource keyed
  at `:parameters`."
  [query-results]
  (let [metadata_cols [:certname :resource :type :title :tags :exported :file :line]
        metadata      (apply juxt metadata_cols)
        groupings     (group-by metadata query-results)]
    (vec (for [[resource params] groupings]
           (assoc (zipmap metadata_cols resource)
                  :parameters
                  (into {} (for [param params :when (:name param)]
                             [(:name param) (json/parse-string (:value param))])))))))

(defn- order-by->tuple
  "Convert an order-by entry from the paging middleware into
  a tuple valid for use by the `ordered-comparator` fn."
  ;; TODO: we should handle the conversion from asc/desc strings
  ;;  to the :ascending :descending keywords in the paging
  ;;  middleware, which should eliminate most or all of the
  ;;  need for this function
  [{:keys [field order]}]
  {:pre [(string? field)
         ((some-fn nil? string?) order)]}
  [(keyword field)
   (if (or (nil? order) (= "asc" (string/lower-case order)))
      :ascending
      :descending)])

(defn- post-process-results
  "Given the results of the query and the optional order-by paging clauses,
  consolidate the results into a form appropriate for returning to the user
  and sort them based on the order-by clauses, if there are any."
  [query-results order-bys]
  {:pre  [(vector? query-results)
          ((some-fn nil? vector?) order-bys)
          (every? map? order-bys)]
   :post [(vector? %)]}
  (let [consolidated-results (consolidate-resource-params query-results)]
    (if (empty? order-bys)
      consolidated-results
      (let [order-bys (map order-by->tuple order-bys)]
        (vec (utils/order-by order-bys consolidated-results))))))

(defn limited-query-resources
  "Take a limit, a map of paging options, and a map of SQL queries as
  produced by `query->sql`, return a map containing the results of the
  query, as well as optional metadata.

  The returned map will contain a key `:result`, whose value is vector of
  resources which match the query.  If the paging-options used to generate
  the queries indicate that a total result count should also be returned, then
  the map will contain an additional key `:count`, whose value is an integer.

   Throws an exception if the query would return more than `limit` results.  (A
   value of `0` for `limit` means that the query should not be limited.)"
  ([limit queries-map]
    (limited-query-resources limit {} queries-map))
  ([limit paging-options {:keys [results-query count-query] :as queries-map}]
    {:pre  [(and (integer? limit) (>= limit 0))]
     :post [(or (zero? limit) (<= (count %) limit))]}
    (let [[query & params] results-query
          limited-query (add-limit-clause limit query)
          query-results (limited-query-to-vec limit (apply vector limited-query params))
          results       {:result
                          (post-process-results
                            query-results
                            (:order-by paging-options))}]
      (if count-query
        (assoc results :count (get-result-count count-query))
        results))))

(defn query-resources
  "Takes a map of SQL queries as produced by `query->sql`, and returns a map
  containing the query results and metadata.  For more detail on the return value,
  see `limited-query-resources`"
  [queries-map]
  {:pre [(map? queries-map)
         (valid-jdbc-query? (:results-query queries-map))]}
    (limited-query-resources 0 queries-map))
