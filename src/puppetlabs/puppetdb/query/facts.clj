(ns puppetlabs.puppetdb.query.facts
  "Fact query generation"
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; SCHEMA

(def row-schema
  (query/wrap-with-supported-fns
    {(s/optional-key :certname) s/Str
     (s/optional-key :max) s/Any
     (s/optional-key :environment) (s/maybe s/Str)
     (s/optional-key :name) s/Str
     (s/optional-key :value) s/Str}))

(def converted-row-schema
  (query/wrap-with-supported-fns
    {(s/optional-key :certname) s/Str
     (s/optional-key :max) s/Any
     (s/optional-key :environment) (s/maybe s/Str)
     (s/optional-key :name) s/Str
     (s/optional-key :value) s/Any}))

;; MUNGE

(pls/defn-validated deserialize-fact-value :- converted-row-schema
  "Coerce values for each row to the proper stored type."
  [row :- row-schema]
  (utils/update-when row [:value] json/parse-string))

(defn munge-result-rows
  [_ _]
  (fn [rows]
    (if (empty? rows)
      []
      (->> rows
           (map deserialize-fact-value)))))

(defn munge-path-result-rows
  [_ _]
  (fn [rows]
     (map #(utils/update-when % [:path] facts/string-to-factpath) rows)))

;; QUERY

(defn fact-paths-query->sql
  [version query paging-options]
  (qe/compile-user-query->sql qe/fact-paths-query query paging-options))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (let [columns (map keyword (keys (dissoc query/fact-columns "value")))]
    (paging/validate-order-by! columns paging-options)
    (qe/compile-user-query->sql qe/facts-query query paging-options)))

;; QUERY + MUNGE

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated and
  expired nodes."
  ([]
   (fact-names {}))
  ([paging-options]
   {:post [(map? %)
           (coll? (:result %))
           (every? string? (:result %))]}
   (paging/validate-order-by! [:name] paging-options)
   (let [order-by-clause (if (:order_by paging-options) "" "ORDER BY name")
         query (format "SELECT DISTINCT name FROM fact_paths %s" order-by-clause)
         facts (query/execute-query [query] paging-options)]
     (update-in facts [:result] #(map :name %)))))
