(ns puppetlabs.puppetdb.query.factsets
  (:require [clojure.set :as set]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.factsets :as factsets]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s])
  (:import  [org.postgresql.util PGobject]))

;; MUNGE

(pls/defn-validated rtj->fact :- factsets/fact-query-schema
  "Converts from the PG row_to_json format back to something real."
  [facts :- {s/Str s/Any}]
  (-> facts
      (set/rename-keys {"f1" :name
                        "f2" :value})
      (update-in [:value] json/parse-string)))

(pls/defn-validated facts->expansion :- factsets/facts-expanded-query-schema
  "Surround the facts response in the href/data format."
  [obj :- (s/maybe PGobject)
   certname :- s/Str
   base-url :- s/Str]
  (let [data-obj {:href (str base-url "/factsets/" certname "/facts")}]
    (if obj
      (assoc data-obj :data (map rtj->fact
                                 (json/parse-string (.getValue obj))))
      data-obj)))

(pls/defn-validated row->factset
  "Convert factset query row into a final factset format."
  [base-url :- s/Str]
  (fn [row]
    (utils/update-when row [:facts] facts->expansion (:certname row) base-url)))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (str url-prefix "/" (name version))]
    (fn [rows]
      (map (row->factset base-url) rows))))

;; QUERY

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
