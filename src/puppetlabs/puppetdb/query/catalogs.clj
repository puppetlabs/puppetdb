(ns puppetlabs.puppetdb.query.catalogs
  "Catalog retrieval

  Returns a catalog in the PuppetDB JSON wire format.  For more info, see
  `documentation/api/wire_format/catalog_format.markdown`."
  (:require [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.catalogs :as cats]
            [schema.core :as s]
            [clojure.set :refer [rename-keys]]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.kitchensink.core :as kitchensink]))

;; v4+ functions

(def catalog-columns
  [:certname
   :version
   :transaction_uuid
   :producer_timestamp
   :environment
   :hash
   :edges
   :resources])

(def row-schema
  {:version (s/maybe String)
   :hash (s/maybe String)
   :transaction_uuid (s/maybe String)
   :environment (s/maybe String)
   :certname String
   :producer_timestamp pls/Timestamp
   :resources (s/maybe org.postgresql.util.PGobject)
   :edges (s/maybe org.postgresql.util.PGobject)})

(defn catalog-response-schema
  "Returns the correct schema for the `version`, use :all for the full-catalog (superset)"
  [api-version]
  (case api-version
    :v4 (assoc (cats/catalog-wireformat :v6)
          :hash String)
    (cats/catalog-wireformat api-version)))

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

(pls/defn-validated munge-resource
  [resource]
  (-> resource
      (rename-keys {"f1" :resource
                    "f2" :type
                    "f3" :title
                    "f4" :tags
                    "f5" :exported
                    "f6" :file
                    "f7" :line
                    "f8" :parameters})
      (update-in [:parameters] #(json/parse-strict-string % true))))

(pls/defn-validated resources-json-final
  [obj :- (s/maybe org.postgresql.util.PGobject)]
  (when obj
    (map munge-resource
         (json/parse-string (.getValue obj)))))

(pls/defn-validated munge-edge
  [edge]
  (-> edge
      (rename-keys {"f1" :source_type
                    "f2" :source_title
                    "f3" :target_type
                    "f4" :target_title
                    "f5" :relationship})))

(pls/defn-validated edges-json-final
  [obj :- (s/maybe org.postgresql.util.PGobject)]
  (when obj
    (map munge-edge
         (json/parse-string (.getValue obj)))))

;; TODO: need to use (catalog-response-schema :v4) as a response schema
(pls/defn-validated convert-catalog
  [row :- row-schema
   {:keys [expand?]}]
  (if expand?
    (-> row
        (update-in [:edges] edges-json-final)
        (update-in [:resources] resources-json-final))
    (-> row
        (assoc :edges {:href (str "/v4/catalogs/" (:certname row) "/edges")})
        (assoc :resources {:href (str "/v4/catalogs/" (:certname row) "/resources")}))))

(pls/defn-validated convert-catalogs
  "Convert catalogs"
  [rows paging-options]
  (map #(convert-catalog % paging-options)
       rows))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [_
   projected-fields :- [s/Keyword]
   paging-options]
  (fn [rows]
    (if (empty? rows)
      []
      (map (qe/basic-project projected-fields)
           (convert-catalogs rows paging-options)))))

(defn query-catalogs
  "Search for catalogs satisfying the given SQL filter."
  [version query-sql]
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
                                (munge-result-rows version projections)))}]
    (if count-query
      (assoc result :count (jdbc/get-result-count count-query))
      result)))

(defn status
  [version node]
  {:pre [string? node]}
  (let [sql (query->sql version ["=" "certname" node])
        results (:result (query-catalogs version sql))]
    (first results)))
