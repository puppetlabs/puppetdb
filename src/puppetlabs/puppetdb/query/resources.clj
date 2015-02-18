(ns puppetlabs.puppetdb.query.resources
  "Resource querying

   This implements resource querying, using the query compiler in
   `puppetlabs.puppetdb.query`, basically by munging the results into the
   right format and picking out the desired columns."
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.utils :as utils]))

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
     (paging/validate-order-by! (map keyword (keys query/resource-columns)) paging-options)
     (qe/compile-user-query->sql qe/resources-query query paging-options)))

(defn parse-params [param-string]
  (if param-string
    (json/parse-string param-string)
    {}))

(defn deserialize-params
  [resources]
  (map #(utils/update-when % [:parameters] parse-params) resources))

(defn munge-result-rows
  "Munge the result rows so that they will be compatible with the version
  specified API specification"
  [_ _ _]
  deserialize-params)

(defn query-resources
  "Search for resources satisfying the given SQL filter."
  [version query-sql]
  {:pre [(map? query-sql)]}
  (let [{[sql & params] :results-query
         count-query    :count-query
         projections    :projections} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                          (comp doall (munge-result-rows version projections)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))
