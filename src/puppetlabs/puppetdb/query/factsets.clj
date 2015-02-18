(ns puppetlabs.puppetdb.query.factsets
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.cheshire :as json]
            [schema.core :as s]
            [clojure.set :refer [rename-keys]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.zip :as zip]
            [puppetlabs.puppetdb.utils :as utils]))

;; SCHEMA

(def row-schema
  {:certname String
   :environment (s/maybe s/Str)
   :hash (s/maybe s/Str)
   :producer_timestamp pls/Timestamp
   :timestamp pls/Timestamp
   :facts (s/maybe org.postgresql.util.PGobject)})

(def facts-schema
  {:name s/Str
   :value s/Any})

(def factset-schema
  {:certname String
   :environment (s/maybe s/Str)
   :timestamp pls/Timestamp
   :producer_timestamp pls/Timestamp
   :hash (s/maybe s/Str)
   :facts (s/either [facts-schema]
                    {:href String})})

;; FUNCS

(pls/defn-validated munge-facts :- facts-schema
  [facts]
  (facts/convert-row-type
   [:type :depth :value_integer :value_float]
   (-> facts
       (rename-keys {"f1" :name
                     "f2" :value
                     "f3" :value_integer
                     "f4" :value_float
                     "f5" :type}))))

(pls/defn-validated facts-to-json-final :- [facts-schema]
  [obj :- (s/maybe org.postgresql.util.PGobject)]
  (when obj
    (map munge-facts
         (json/parse-string (.getValue obj)))))

(pls/defn-validated convert-factset :- factset-schema
  [row :- row-schema
   {:keys [expand?]}]
  (if expand?
    (update-in row [:facts] facts-to-json-final)
    (assoc row :facts {:href (str "/v4/factsets/" (:certname row) "/facts")})))

(pls/defn-validated convert-factsets
  [rows paging-options]
  (map #(convert-factset % paging-options)
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
           (convert-factsets rows paging-options)))))

(pls/defn-validated query->sql
  "Compile a query into an SQL expression."
  [version :- s/Keyword
   query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/factset-columns)) paging-options)
  (qe/compile-user-query->sql
   qe/factsets-query query paging-options))
