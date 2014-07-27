;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:require [com.puppetlabs.jdbc :as jdbc]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.query :as query]
            [clojure.edn :as clj-edn]
            [com.puppetlabs.puppetdb.schema :as pls]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.facts :as f]
            [clojure.string :as str]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

(def row-schema
  {:certname String
   (s/optional-key :environment) (s/maybe s/Str)
   :path String
   :name s/Str
   :depth s/Int
   :value s/Any
   :type (s/maybe String)})

(def converted-row-schema
  {:certname s/Str
   :path s/Str
   :name s/Str
   :value s/Any
   (s/optional-key :environment) (s/maybe s/Str)})

(def fact-schema
  {:certname s/Str
   :name s/Str
   :value s/Any
   (s/optional-key :environment) (s/maybe s/Str)})

(defn convert-row-type
  "Coerce the value of a row to the proper type."
  [dissociated-fields row]
  (let [conversion (case (:type row)
                     "boolean" clj-edn/read-string
                     "float" (comp double clj-edn/read-string)
                     "integer" (comp biginteger clj-edn/read-string)
                     ("string" "null") identity)]
    (reduce #(dissoc %1 %2)
            (update-in row [:value] conversion)
            dissociated-fields)))

(pls/defn-validated convert-types :- [converted-row-schema]
  [rows :- [row-schema]]
  (map (partial convert-row-type [:type :depth]) rows))

(defn stringify-value
  [value]
  (if (string? value) value (json/generate-string value)))

(pls/defn-validated collapse-facts :- fact-schema
  "Aggregate all facts for a factname into a single structure."
  [version
   certname-rows :- [converted-row-schema]]
  (let [first-row (first certname-rows)
        facts (reduce f/recreate-fact-path {} certname-rows)
        keyval (f/int-maps->vectors facts)
        conversion (if (= version :v4) identity stringify-value)]
    (assoc (select-keys first-row [:certname :environment :timestamp :name])
      :value (conversion (first (vals keyval))))))

(defn structured-data-seq
  "Produce a lazy sequence of facts from a list of rows ordered by fact name"
  ([version rows pred collapsing-fn conversion-fn]
  (when (seq rows)
    (let [[certname-facts more-rows] (split-with (pred rows) rows)]
      (cons ((comp (partial collapsing-fn version) conversion-fn) certname-facts)
            (lazy-seq (structured-data-seq version more-rows pred
                                           collapsing-fn conversion-fn)))))))


(defn facts-sql
  "Return a vector with the facts SQL query string as the first element,
  parameters needed for that query as the rest."
  [operators query]
  (if query
    (let [[subselect & params] (query/fact-query->sql operators query)
          sql (format "SELECT facts.certname, facts.environment, facts.name,
                       facts.value, facts.path, facts.type, facts.depth
                      FROM (%s) facts" subselect)]
      (apply vector sql params))
      ["SELECT fs.certname, fp.path as path, fp.name as name, fp.depth as depth,
                        COALESCE(fv.value_string,
                                cast(fv.value_integer as text),
                                cast(fv.value_boolean as text),
                                cast(fv.value_float as text)) as value,
                        vt.type as type,
                        env.name as environment
                FROM factsets fs
                      INNER JOIN facts as f on fs.id = f.factset_id
                      INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                      INNER JOIN fact_paths as fp on fv.path_id = fp.id
                      INNER JOIN value_types as vt on vt.id=fv.value_type_id
                      LEFT OUTER JOIN environments as env on fs.environment_id = env.id
        ORDER BY name, fs.certname"]))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (let [augmented-paging-options (f/augment-paging-options paging-options)
        columns (if (contains? #{:v2 :v3} version)
                  (map keyword (keys query/fact-columns))
                  (map keyword (keys (dissoc query/fact-columns "value"))))]
    (paging/validate-order-by! columns paging-options)
    (case version
      (:v2 :v3)
      (let [operators (query/fact-operators version)
            [sql & params] (facts-sql operators query)]
        (conj {:results-query (apply vector (jdbc/paged-sql sql augmented-paging-options true) params)}
              (when (:count? augmented-paging-options)
                [:count-query (apply vector (jdbc/count-sql true sql) params)])))
      (qe/compile-user-query->sql
        qe/facts-query query paging-options))))

(defn flat-facts-by-node
  "Similar to `facts-for-node`, but returns facts in the form:

    [{:certname <node> :name <fact> :value <value>}
     ...
     {:certname <node> :name <fact> :value <value>}]"
  [node]
  (jdbc/query-to-vec
   ["SELECT fs.certname,
             fp.path as name,
             COALESCE(fv.value_string,
                      cast(fv.value_integer as text),
                      cast(fv.value_boolean as text),
                      cast(fv.value_float as text),
                      '') as value
             FROM factsets fs
                  INNER JOIN facts as f on fs.id = f.factset_id
                  INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                  INNER JOIN fact_paths as fp on fv.path_id = fp.id
             WHERE fp.depth = 0 AND
                   certname = ?"
     node]))

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
                 ["SELECT DISTINCT path as name
                   FROM fact_paths
                   WHERE depth = 0
                   ORDER BY path"]
                 paging-options)]
      (update-in facts [:result] #(map :name %)))))
