(ns puppetlabs.puppetdb.query.edges
  "Fact query generation"
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

;; SCHEMA

(def edge-schema
  {(s/optional-key :certname) String
   (s/optional-key :relationship) String
   (s/optional-key :source_title) String
   (s/optional-key :source_type) String
   (s/optional-key :target_title) String
   (s/optional-key :target_type) String})

;; MUNGE

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [_ _]
  (fn [rows]
    (map #(s/validate edge-schema %) rows)))

;; QUERY

(def edge-columns
  [:certname
   :relationship
   :source_title
   :source_type
   :target_title
   :target_type])

(defn query->sql
  "Compile a query into a SQL expression."
  ([version query]
     (query->sql version query {}))
  ([version query paging-options]
     {:pre [((some-fn nil? sequential?) query) ]
      :post [(map? %)
             (string? (first (:results-query %)))
             (every? (complement coll?) (rest (:results-query %)))]}
     (paging/validate-order-by! edge-columns paging-options)
     (qe/compile-user-query->sql qe/edges-query query paging-options)))

;; QUERY + MUNGE

(defn query-edges
  "Search for edges satisfying the given SQL filter."
  [version query-sql url-prefix]
  {:pre [[(map? query-sql)]]}
  (let [{:keys [results-query count-query]} query-sql
        munge-fn (munge-result-rows version url-prefix)]
    (cond-> {:result (->> (jdbc/with-query-results-cursor results-query)
                          munge-fn
                          (into []))}
      count-query (assoc :count (jdbc/get-result-count count-query)))))
