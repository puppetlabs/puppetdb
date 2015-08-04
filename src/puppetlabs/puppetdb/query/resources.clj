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
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; SCHEMA

(def row-schema
  "Resource query row schema."
  (query/wrap-with-supported-fns
    {(s/optional-key :certname) s/Str
     (s/optional-key :environment) (s/maybe s/Str)
     (s/optional-key :exported) s/Bool
     (s/optional-key :file) (s/maybe s/Str)
     (s/optional-key :line) (s/maybe s/Int)
     (s/optional-key :parameters) (s/maybe s/Str)
     (s/optional-key :resource) s/Str
     (s/optional-key :tags) [(s/maybe s/Str)]
     (s/optional-key :title) s/Str
     (s/optional-key :type) s/Str}))

(def resource-parameters-schema
  "Schema for resource parameters."
  {s/Any s/Any})

(def resource-schema
  "Schema for validating a single resource."
  (query/wrap-with-supported-fns
    {(s/optional-key :certname) s/Str
     (s/optional-key :environment) (s/maybe s/Str)
     (s/optional-key :exported) s/Bool
     (s/optional-key :file) (s/maybe s/Str)
     (s/optional-key :line) (s/maybe s/Int)
     (s/optional-key :parameters) resource-parameters-schema
     (s/optional-key :resource) s/Str
     (s/optional-key :tags) [(s/maybe s/Str)]
     (s/optional-key :title) s/Str
     (s/optional-key :type) s/Str}))

;; MUNGE

(pls/defn-validated parse-params :- resource-parameters-schema
  "If there is a param string, parse it to JSON else return an empty hash."
  [param-string :- (s/maybe s/Str)]
  (if param-string
    (json/parse-string param-string)
    {}))

(pls/defn-validated row->resource :- resource-schema
  "Convert resource query row into a final resource format."
  [row :- row-schema]
  (utils/update-when row [:parameters] parse-params))

(pls/defn-validated munge-result-rows
  "Munge the result rows so that they will be compatible with the version
  specified API specification"
  [_ _]
  (fn [rows]
    (if (empty? rows)
      []
      (map row->resource rows))))

;; QUERY

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
     (qe/compile-user-query->sql
      qe/resources-query query paging-options)))
