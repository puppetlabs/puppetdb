(ns puppetlabs.puppetdb.query.edges
  "Fact query generation"
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.query-eng.engine :as qe]))

;; TODO should this contain certname?

(def edge-schema
  {:relationship String
   :source_title String
   :target_title String
   :target_type String
   :certname String
   :source_type String})

(def edge-columns
  [:relationship
   :source_title
   :target_title
   :certname
   :target_type
   :source_type])

(pls/defn-validated munge-result-rows
  [_
   projected-fields :- [s/Keyword]
   _]
  (fn [rows]
    (if (empty? rows)
      []
      rows)))

(defn query->sql
  "Compile a query into a SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! edge-columns paging-options)
  (qe/compile-user-query->sql qe/edges-query query paging-options))
