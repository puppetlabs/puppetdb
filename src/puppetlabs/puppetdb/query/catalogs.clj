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

(pls/defn-validated row->catalog
  "Return a function that will convert a catalog query row into a final catalog format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:edges] utils/child->expansion :catalogs :edges base-url)
        (utils/update-when [:resources] utils/child->expansion :catalogs :resources base-url))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (utils/as-path url-prefix (name version))]
    (fn [rows]
      (map (row->catalog base-url) rows))))

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
  (let [{:keys [results-query count-query]} query-sql
        munge-fn (munge-result-rows version url-prefix)]
    (cond-> {:result (->> (jdbc/with-query-results-cursor results-query)
                          munge-fn
                          (into []))}
        count-query (assoc :count (jdbc/get-result-count count-query)))))

(defn status
  [version node url-prefix]
  {:pre [string? node]}
  (let [sql (query->sql version ["=" "certname" node])
        results (:result (query-catalogs version sql url-prefix))]
    (first results)))
