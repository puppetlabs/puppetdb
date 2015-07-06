(ns com.puppetlabs.puppetdb.query.factsets
  (:require [com.puppetlabs.puppetdb.query-eng :as qe]
            [com.puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.facts :as facts]
            [com.puppetlabs.puppetdb.zip :as zip]))

;; SCHEMA

(def row-schema
  {:certname String
   :environment (s/maybe s/Str)
   :path String
   :value s/Any
   :value_float (s/maybe s/Num)
   :value_integer (s/maybe s/Int)
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

;; FUNCS

(defn create-certname-pred
  "Create a function to compare the certnames in a list of
  rows with that of the first row."
  [rows]
  (let [certname (:certname (first rows))]
    (fn [row]
      (= certname (:certname row)))))

(pls/defn-validated convert-types :- [converted-row-schema]
  [rows :- [row-schema]]
  (map (partial facts/convert-row-type [:type :value_integer :value_float]) rows))

(defn int-map->vector
  "Convert a map of form {1 'a' 0 'b' ...} to vector ['b' 'a' ...]"
  [node]
  (when (and
         (map? node)
         (not (empty? node)))
    (let [int-keys (keys node)]
      (when (every? integer? int-keys)
        (mapv node (sort int-keys))))))

(defn int-maps->vectors
  "Walk a structured fact set, transforming all int maps."
  [facts]
  (:node (zip/post-order-transform (zip/tree-zipper facts)
                                   [int-map->vector])))

(defn recreate-fact-path
  "Produce the nested map corresponding to a path/value pair.

   Operates by accepting an existing map `acc` and a map containing keys `path`
   and `value`, it splits the path into its components and populates the data
   structure with the `value` in the correct path.

   Returns the complete map structure after this operation is applied to
   `acc`."
  [acc {:keys [path value]}]
  (let [split-path (facts/string-to-factpath path)]
    (assoc-in acc split-path value)))

(pls/defn-validated collapse-factset :- factset-schema
  "Aggregate all facts for a certname into a single structure."
  [version :- s/Keyword
   certname-rows :- [converted-row-schema]]
  (let [first-row (first certname-rows)
        facts (reduce recreate-fact-path {} certname-rows)]
    (assoc (select-keys first-row [:certname :environment :timestamp])
           :facts (int-maps->vectors facts))))

(pls/defn-validated structured-data-seq
  "Produce a lazy sequence of facts from a list of rows ordered by fact name"
  [version :- s/Keyword
   rows]
  (when (seq rows)
    (let [[certname-facts more-rows] (split-with (create-certname-pred rows) rows)]
      (cons ((comp (partial collapse-factset version) convert-types) certname-facts)
            (lazy-seq (structured-data-seq version more-rows))))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword]
  (fn [rows]
    (if (empty? rows)
      []
      (structured-data-seq version rows))))

(pls/defn-validated query->sql
  "Compile a query into an SQL expression."
  [version :- s/Keyword
   query paging-options]
  {:pre [((some-fn nil? sequential?) query)]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/factset-columns)) paging-options)
  (qe/compile-user-query->sql
   qe/factsets-query query paging-options))
