(ns com.puppetlabs.puppetdb.query.factsets
  (:require [com.puppetlabs.puppetdb.query-eng :as qe]
            [clojure.edn :as clj-edn]
            [com.puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.facts :as f]
            [com.puppetlabs.cheshire :as json]))

(def row-schema
  {:certname String
   :environment (s/maybe s/Str)
   :path String
   :value s/Any
   :type (s/maybe String)
   :timestamp pls/Timestamp})

(def converted-row-schema
  {:certname String
   :environment (s/maybe s/Str)
   :path String
   :value s/Any
   :timestamp pls/Timestamp})

(def factset-schema
  {:certname String
   :environment (s/maybe s/Str)
   :timestamp pls/Timestamp
   :facts {s/Str s/Any}})

(defn convert-row-type
  "Coerce the value of a row to the proper type."
  [row]
  (let [conversion (case (:type row)
                     "boolean" clj-edn/read-string
                     "float" (comp double clj-edn/read-string)
                     "integer" (comp biginteger clj-edn/read-string)
                     ("string" "null") identity)]
    (dissoc (update-in row [:value] conversion) :type)))

(pls/defn-validated convert-types :- [converted-row-schema]
  [rows :- [row-schema]]
  (map convert-row-type rows))

(pls/defn-validated collapse-facts :- factset-schema
  "Aggregate all facts for a certname into a single structure."
  [certname-rows :- [converted-row-schema]]
  (let [first-row (first certname-rows)
        facts (reduce f/recreate-fact-path {} certname-rows)]
    (assoc (select-keys first-row [:certname :environment :timestamp])
      :facts (f/int-maps->vectors facts))))

(pls/defn-validated collapsed-fact-seq
  "Produce a sequence of factsets from a list of rows ordered by certname."
  [rows]
  (when (seq rows)
    (let [[certname-facts more-rows] (split-with (f/create-certname-pred rows) rows)]
      (cons ((comp collapse-facts convert-types) certname-facts)
            (lazy-seq (collapsed-fact-seq more-rows))))))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/fact-columns)) paging-options)
  (case version
    (:v2 :v3)
    (throw (IllegalArgumentException. "Factset endpoint is only availble for v4"))

    (qe/compile-user-query->sql
     qe/factsets-query query paging-options)))
