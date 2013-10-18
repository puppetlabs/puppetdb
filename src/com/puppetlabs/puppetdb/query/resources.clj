;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resources
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.string :as string]
            [com.puppetlabs.utils :as utils])
  (:use [com.puppetlabs.jdbc :only [limited-query-to-vec
                                    convert-result-arrays
                                    with-transacted-connection
                                    add-limit-clause
                                    valid-jdbc-query?
                                    get-result-count
                                    paged-sql
                                    count-sql
                                    with-query-results-cursor]]
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
    (validate-order-by! (map keyword (keys resource-columns)) paging-options)
    (let [[subselect & params] (resource-query->sql operators query)
          sql (format (str "SELECT subquery1.certname, subquery1.resource, "
                                  "subquery1.type, subquery1.title, subquery1.tags, "
                                  "subquery1.exported, subquery1.file, "
                                  "subquery1.line, rpc.parameters "
                            "FROM (%s) subquery1 "
                            "LEFT OUTER JOIN resource_params_cache rpc "
                                "ON rpc.resource = subquery1.resource")
                subselect)
          paged-select (paged-sql sql paging-options)
          ;; This is a little more complex than I'd prefer; the general query paging
          ;;  functions are built to work for SQL queries that return 1 row per
          ;;  PuppetDB result.  Since that's not the case for resources right now,
          ;;  we have to actually manage two separate SQL queries.  The introduction
          ;;  of the new `resource-params-cache` table in the next release should
          ;;  alleviate this problem and allow us to simplify this code.
          result               {:results-query (apply vector paged-select params)}]
      (if (:count? paging-options)
        (assoc result :count-query (apply vector (count-sql subselect) params))
        result))))

(def v1-query->sql
  (partial query->sql resource-operators-v1))

(def v2-query->sql
  (partial query->sql resource-operators-v2))

(def v3-query->sql
  (partial query->sql resource-operators-v3))

(defn deserialize-params
  [resources]
  (let [parse-params #(if % (json/parse-string %) {})]
    (map #(update-in % [:parameters] parse-params) resources)))

(defn with-queried-resources
  [query params func]
  (let [parse-params #(if % (json/parse-string %) {})]
    (with-query-results-cursor query params rs
      (func (deserialize-params rs)))))

(defn limited-query-resources
  "Take a limit, and a map of SQL queries as produced by `query->sql`, return
  a map containing the results of the query, as well as optional metadata.

  The returned map will contain a key `:result`, whose value is vector of
  resources which match the query.  If the paging-options used to generate
  the queries indicate that a total result count should also be returned, then
  the map will contain an additional key `:count`, whose value is an integer.

   Throws an exception if the query would return more than `limit` results.  (A
   value of `0` for `limit` means that the query should not be limited.)"
  [limit {:keys [results-query count-query] :as queries-map}]
  {:pre  [(and (integer? limit) (>= limit 0))]
   :post [(or (zero? limit) (<= (count %) limit))]}
  (let [[query & params] results-query
        limited-query (add-limit-clause limit query)
        results       (limited-query-to-vec limit (apply vector limited-query params))
        metadata_cols [:certname :resource :type :title :tags :exported :file :line]
        metadata      (apply juxt metadata_cols)
        results       {:result (deserialize-params results)}]
    (if count-query
      (assoc results :count (get-result-count count-query))
      results)))

(defn query-resources
  "Takes a map of SQL queries as produced by `query->sql`, and returns a map
  containing the query results and metadata.  For more detail on the return value,
  see `limited-query-resources`"
  [queries-map]
  {:pre [(map? queries-map)
         (valid-jdbc-query? (:results-query queries-map))]}
    (limited-query-resources 0 queries-map))
