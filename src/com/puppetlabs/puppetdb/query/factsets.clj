(ns com.puppetlabs.puppetdb.query.factsets
  (:require [com.puppetlabs.puppetdb.query-eng :as qe]
            [com.puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.facts :as f]
            [com.puppetlabs.puppetdb.query.facts :as facts]
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

(pls/defn-validated convert-types :- [converted-row-schema]
  [rows :- [row-schema]]
  (map (partial facts/convert-row-type [:type]) rows))

(pls/defn-validated collapse-factset :- factset-schema
  "Aggregate all facts for a certname into a single structure."
  [version
   certname-rows :- [converted-row-schema]]
  (let [first-row (first certname-rows)
        facts (reduce f/recreate-fact-path {} certname-rows)]
    (assoc (select-keys first-row [:certname :environment :timestamp])
      :facts (f/int-maps->vectors facts))))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/factset-columns)) paging-options)
    (qe/compile-user-query->sql
     qe/factsets-query query paging-options))
