;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resources
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

(defn- aggregate-resource-parameters
  "TODO docs & conditions"
  [query-results]
  (let [metadata_cols [:certname :resource :type :title :tags :exported :file :line]
        metadata      (apply juxt metadata_cols)
        groupings     (group-by metadata query-results)]
    (vec (for [[resource params] groupings]
           (assoc (zipmap metadata_cols resource)
                  :parameters
                  (into {} (for [param params :when (:name param)]
                             [(:name param) (json/parse-string (:value param))])))))))

(defn- build-comp-fn
  "TODO docs & conditions"
  [{:keys [field order]}]
  {:pre  [(keyword? field)
          (or (= order "ASC")
              (= order "DESC")
              (nil? order))]}
  (fn [x y]
    (if (or (= order "ASC") (nil? order))
      (compare (x field) (y field))
      (compare (y field) (x field)))))

(defn- combine-comp-fns
  "TODO docs & conditions"
  [comp-fn1 comp-fn2]
  (fn [x y]
    (let [val1 (comp-fn1 x y)]
      (if (= val1 0)
        (comp-fn2 x y)
        val1))))

(defn- sort-on-order-by
  "TODO docs & conditions"
  [query-results order-bys]
  (let [order-bys  (mapv #(update-in % [:field] keyword) order-bys)
        comp-fns   (map build-comp-fn order-bys)
        final-comp (reduce combine-comp-fns comp-fns)
        sorted     (sort final-comp query-results)]
    (vec sorted)))

(defn- post-process-results
  "TODO docs & conditions"
  [query-results {:keys [order-by]}]
  (let [aggregated-results (aggregate-resource-parameters query-results)]
    (if (empty? order-by)
      aggregated-results
      (sort-on-order-by aggregated-results order-by))))

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
          results       {:result (post-process-results query-results paging-options)}]
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
