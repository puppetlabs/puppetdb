(ns puppetlabs.puppetdb.query.facts
  "Fact query generation"
  (:require [puppetlabs.puppetdb.jdbc :as jdbc]
            [schema.core :as s]
            [puppetlabs.puppetdb.query :as query]
            [clojure.edn :as clj-edn]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.query-eng.engine :as qe]))

;; SCHEMA

(def row-schema
  {(s/optional-key :certname) String
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :path) String
   (s/optional-key :name) s/Str
   (s/optional-key :depth) s/Int
   (s/optional-key :value_integer) (s/maybe s/Int)
   (s/optional-key :value_float) (s/maybe s/Num)
   (s/optional-key :value) s/Any
   (s/optional-key :type) (s/maybe String)})

(def converted-row-schema
  {(s/optional-key :certname) s/Str
   (s/optional-key :path) s/Str
   (s/optional-key :name) s/Str
   (s/optional-key :value) s/Any
   (s/optional-key :environment) (s/maybe s/Str)})

(def fact-schema
  {:certname s/Str
   :name s/Str
   :value s/Any
   (s/optional-key :environment) (s/maybe s/Str)})

;; FUNCS

(defn stringify-value
  [value]
  (if (string? value) value (json/generate-string value)))

(pls/defn-validated convert-types :- [converted-row-schema]
  "Coerce values for each row to the proper stored type."
  [rows :- [row-schema]]
  (map (partial facts/convert-row-type [:type :depth :value_integer :value_float]) rows))

(defn munge-result-rows
  [version projections]
  (fn [rows]
    (if (empty? rows)
      []
      (->> rows
        convert-types
        (map #(select-keys % (or (seq projections)
                                 [:certname :environment :timestamp :name :value])))))))

(defn fact-paths-query->sql
  [version query paging-options]
  (qe/compile-user-query->sql qe/fact-paths-query query paging-options))

(defn munge-path-result-rows
  [rows]
  (map #(update-in % [:path] facts/string-to-factpath) rows))

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

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated nodes."
  ([]
     (fact-names {}))
  ([paging-options]
     {:post [(map? %)
             (coll? (:result %))
             (every? string? (:result %))]}
     (paging/validate-order-by! [:name] paging-options)
     (let [facts (query/execute-query
                  ["SELECT DISTINCT name
                   FROM fact_paths
                   ORDER BY name"]
                  paging-options)]
       (update-in facts [:result] #(map :name %)))))
