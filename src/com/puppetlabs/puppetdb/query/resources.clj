;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resources
  (:require [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

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
   (case version
     (:v2 :v3)
     (let [operators (query/resource-operators version)
           [subselect & params] (query/resource-query->sql operators query)
           sql (format (str "SELECT subquery1.certname, subquery1.resource, "
                            "subquery1.type, subquery1.title, subquery1.tags, "
                            "subquery1.exported, subquery1.file, "
                            "subquery1.line, rpc.parameters, subquery1.environment "
                            "FROM (%s) subquery1 "
                            "LEFT OUTER JOIN resource_params_cache rpc "
                            "ON rpc.resource = subquery1.resource")
                       subselect)]
       (conj {:results-query (apply vector (jdbc/paged-sql sql paging-options) params)}
             (when (:count? paging-options)
               [:count-query (apply vector (jdbc/count-sql subselect) params)])))

     (qe/compile-user-query->sql
      qe/resources-query query paging-options))))

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

(defn query-resources
  "Search for resources satisfying the given SQL filter."
  [version query-sql]
  {:pre [(map? query-sql)]}
  (let [{[sql & params] :results-query
         count-query    :count-query} query-sql
        result {:result (query/streamed-query-result
                         version sql params
                          ;; The doall simply forces the seq to be traversed
                          ;; fully.
                         (comp doall (munge-result-rows version)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))
