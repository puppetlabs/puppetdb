;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resources
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.string :as string]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :refer [resource-query->sql
                                                  resource-operators
                                                  resource-columns]]
            [com.puppetlabs.puppetdb.query.paging :refer [validate-order-by!]]))

(defn query->sql
  "Compile a resource `query` and an optional `paging-options` map, using the
  specified set of `operators`, into the SQL expressions necessary to retrieve
  the required data.

  The return value is a map.  It contains a key `:results-query`, which contains
  the SQL needed to retrieve the matching resources.  If the `paging-options`
  indicate that the user would also like a total count of available results,
  then the return value will also contain a key `:count-query` whose value
  contains the SQL necessary to retrieve the count data."
  ([version query]
    (query->sql version query {}))
  ([version query paging-options]
   {:pre  [(sequential? query)]
    :post [(map? %)
           (jdbc/valid-jdbc-query? (:results-query %))
           (or
             (not (:count? paging-options))
             (jdbc/valid-jdbc-query? (:count-query %)))]}
    (validate-order-by! (map keyword (keys resource-columns)) paging-options)
    (let [operators (resource-operators version)
          [subselect & params] (resource-query->sql operators query)
          sql (format (str "SELECT subquery1.certname, subquery1.resource, "
                                  "subquery1.type, subquery1.title, subquery1.tags, "
                                  "subquery1.exported, subquery1.file, "
                                  "subquery1.line, rpc.parameters, subquery1.environment "
                            "FROM (%s) subquery1 "
                            "LEFT OUTER JOIN resource_params_cache rpc "
                                "ON rpc.resource = subquery1.resource")
                subselect)
          paged-select (jdbc/paged-sql sql paging-options)
          ;; This is a little more complex than I'd prefer; the general query paging
          ;;  functions are built to work for SQL queries that return 1 row per
          ;;  PuppetDB result.  Since that's not the case for resources right now,
          ;;  we have to actually manage two separate SQL queries.  The introduction
          ;;  of the new `resource-params-cache` table in the next release should
          ;;  alleviate this problem and allow us to simplify this code.
          result               {:results-query (apply vector paged-select params)}]
      (if (:count? paging-options)
        (assoc result :count-query (apply vector (jdbc/count-sql subselect) params))
        result))))

(defn deserialize-params
  [resources]
  (let [parse-params #(if % (json/parse-string %) {})]
    (map #(update-in % [:parameters] parse-params) resources)))

(defn munge-result-rows
  "Munge the result rows so that they will be compatible with the version
  specified API specification"
  [version]
  (let [rename-file-line
        (fn [rows]
          (map #(clojure.set/rename-keys % {:file :sourcefile
                                            :line :sourceline})
               rows))]
    (case version
      :v2 (comp deserialize-params rename-file-line)
      deserialize-params)))

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
        limited-query (jdbc/add-limit-clause limit query)
        results       (jdbc/limited-query-to-vec limit (apply vector limited-query params))
        results       {:result (deserialize-params results)}]
    (if count-query
      (assoc results :count (jdbc/get-result-count count-query))
      results)))

(defn query-resources
  "Takes a map of SQL queries as produced by `query->sql`, and returns a map
  containing the query results and metadata.  For more detail on the return value,
  see `limited-query-resources`"
  [queries-map]
  {:pre [(map? queries-map)
         (jdbc/valid-jdbc-query? (:results-query queries-map))]}
    (limited-query-resources 0 queries-map))
