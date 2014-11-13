(ns puppetlabs.puppetdb.query.fact-contents
  (:require [puppetlabs.puppetdb.facts :as f]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(def row-schema
  {(s/optional-key :certname) s/Str
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :path) s/Str
   (s/optional-key :name) s/Str
   (s/optional-key :value) (s/maybe s/Str)
   (s/optional-key :value_integer) (s/maybe s/Int)
   (s/optional-key :value_float) (s/maybe s/Num)
   (s/optional-key :type) s/Str})

(def converted-row-schema
  {(s/optional-key :certname) s/Str
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :path) f/fact-path
   (s/optional-key :name) s/Str
   (s/optional-key :value) s/Any})

(pls/defn-validated munge-result-row :- converted-row-schema
  "Coerce the value of a row to the proper type, and convert the path back to
   an array structure."
  [row :- row-schema]
  (-> row
      (update-in [:value] #(or (:value_integer row) (:value_float row)
                               (f/unstringify-value (:type row) %)))
      (update-in [:path] f/string-to-factpath)
      (dissoc :type :value_integer :value_float)))

(defn fact-contents-project
  "Returns a function will remove non-projected columns if projections is specified."
  [projections]
  (if (seq projections)
    #(select-keys % projections)
    identity))

(defn munge-result-rows
  "Munge resulting rows for fact-contents endpoint."
  [version projections]
  (fn [rows]
    (map (comp (fact-contents-project projections) munge-result-row) rows)))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query)]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (qe/compile-user-query->sql qe/fact-contents-query query paging-options))
