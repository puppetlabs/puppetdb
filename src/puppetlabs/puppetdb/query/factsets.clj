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

(pls/defn-validated row->factset
  "Convert factset query row into a final factset format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:facts] utils/child->expansion :factsets :facts base-url)
        (utils/update-when [:facts :data] (partial map #(update % :value json/parse-string))))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (utils/as-path url-prefix (name version))]
    (partial map (row->factset base-url))))

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
