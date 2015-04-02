(ns puppetlabs.puppetdb.query.catalogs
  "Catalog retrieval"
  (:require [clojure.set :as set]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s])
  (:import  [org.postgresql.util PGobject]))

;; MUNGE

(pls/defn-validated rtj->resource :- catalogs/resource-query-schema
  "Convert the row_to_json PG output to real data, and parse parameters
  from its JSON storage."
  [resource :- {s/Str s/Any}]
  (-> resource
      (set/rename-keys {"f1" :resource
                        "f2" :type
                        "f3" :title
                        "f4" :tags
                        "f5" :exported
                        "f6" :file
                        "f7" :line
                        "f8" :parameters})))

(pls/defn-validated resources->expansion :- {:href s/Str (s/optional-key :data) [s/Any]}
  "Convert the resources data to a href/data style object."
  [obj :- (s/maybe PGobject)
   certname :- s/Str
   base-url :- s/Str]
  (let [data-obj {:href (str base-url "/catalogs/" certname "/resources")}]
    (if obj
      (assoc data-obj :data (map rtj->resource
                                 (json/parse-string (.getValue obj))))
      data-obj)))

(pls/defn-validated rtj->edge :- catalogs/edge-query-schema
  "Convert the row_to_json PG output to real data."
  [edge :- {s/Str s/Any}]
  (-> edge
      (set/rename-keys {"f1" :source_type
                        "f2" :source_title
                        "f3" :target_type
                        "f4" :target_title
                        "f5" :relationship})))

(pls/defn-validated edges->expansion :- {:href s/Str (s/optional-key :data) [s/Any]}
  "Convert the edges data to the expanded format."
  [obj :- (s/maybe PGobject)
   certname :- s/Str
   base-url :- s/Str]
  (let [data-obj {:href (str base-url "/catalogs/" certname "/edges")}]
    (if obj
      (assoc data-obj :data (map rtj->edge
                                 (json/parse-string (.getValue obj))))
      data-obj)))

(pls/defn-validated row->catalog
  "Return a function that will convert a catalog query row into a final catalog format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:edges] edges->expansion (:certname row) base-url)
        (utils/update-when [:resources] resources->expansion (:certname row) base-url))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   projected-fields :- [s/Keyword]
   _
   url-prefix :- s/Str]
  (let [base-url (str url-prefix "/" (name version))]
    (fn [rows]
      (map (comp (qe/basic-project projected-fields)
                 (row->catalog base-url))
           rows))))

;; QUERY

(def catalog-columns
  [:certname
   :version
   :transaction_uuid
   :producer_timestamp
   :environment
   :hash
   :edges
   :resources])

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  ([version query]
   (query->sql version query {}))
  ([_ query paging-options]
   {:pre  [((some-fn nil? sequential?) query)]
    :post [(map? %)
           (jdbc/valid-jdbc-query? (:results-query %))
           (or (not (:count? paging-options))
               (jdbc/valid-jdbc-query? (:count-query %)))]}
   (paging/validate-order-by! catalog-columns paging-options)
   (qe/compile-user-query->sql
    qe/catalog-query query paging-options)))

;; QUERY + MUNGE

(defn query-catalogs
  "Search for catalogs satisfying the given SQL filter."
  [version query-sql url-prefix]
  {:pre  [(map? query-sql)
          (jdbc/valid-jdbc-query? (:results-query query-sql))]
   :post [(map? %)
          (sequential? (:result %))]}
  (let [{[sql & params] :results-query
         count-query    :count-query
         projections    :projections} query-sql
         result {:result (query/streamed-query-result
                          version sql params
                          (comp doall
                                (munge-result-rows version projections {} url-prefix)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn status
  [version node url-prefix]
  {:pre [string? node]}
  (let [sql (query->sql version ["=" "certname" node])
        results (:result (query-catalogs version sql url-prefix))]
    (first results)))
