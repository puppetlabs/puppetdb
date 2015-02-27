;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:require [com.puppetlabs.jdbc :as jdbc]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.query :as query]
            [clojure.edn :as clj-edn]
            [com.puppetlabs.puppetdb.schema :as pls]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.facts :as facts]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

;; SCHEMA

(def row-schema
  {:certname String
   (s/optional-key :environment) (s/maybe s/Str)
   :path String
   :name s/Str
   :depth s/Int
   :value_integer (s/maybe s/Int)
   :value_float (s/maybe s/Num)
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

;; FUNCS

(defn stringify-value
  [value]
  (if (string? value) value (json/generate-string value)))

(pls/defn-validated convert-types :- [converted-row-schema]
  "Coerce values for each row to the proper stored type."
  [rows :- [row-schema]]
  (map (partial facts/convert-row-type [:type :depth :value_integer :value_float]) rows))

(defn munge-result-rows
  [version]
  (fn [rows]
    (if (empty? rows)
      []
      (let [new-rows (->> rows
                          convert-types
                          (map #(select-keys % [:certname :environment :timestamp :name :value])))]
        (case version
          (:v2 :v3) (map #(update-in % [:value] stringify-value) new-rows)
          new-rows)))))

(defn facts-sql
  "Return a vector with the facts SQL query string as the first element,
  parameters needed for that query as the rest."
  [operators query]
  (if query
    (let [[subselect & params] (query/fact-query->sql operators query)
          sql (format "SELECT facts.certname, facts.environment, facts.name,
                       facts.value, facts.path, facts.type, facts.depth,
                       facts.value_float, facts.value_integer
                      FROM (%s) facts" subselect)]
      (apply vector sql params))
    ["SELECT fs.certname,
             fp.path as path,
             fp.name as name,
             fp.depth as depth,
             fv.value_float as value_float,
             fv.value_integer as value_integer,
             fv.value_hash as value_hash,
             COALESCE(fv.value_string,
                      fv.value_json,
                      cast(fv.value_float as value_float as text),
                      cast(fv.value_integer as value_integer as text),
                      cast(fv.value_boolean as text)) as value,
             vt.type as type,
             env.name as environment
        FROM factsets fs
          INNER JOIN facts as f on fs.id = f.factset_id
          INNER JOIN fact_values as fv on f.fact_value_id = fv.id
          INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
          INNER JOIN value_types as vt on vt.id=fv.value_type_id
          LEFT OUTER JOIN environments as env on fs.environment_id = env.id
        WHERE depth = 0"]))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (let [columns (if (contains? #{:v2 :v3} version)
                  (map keyword (keys (dissoc query/fact-columns "environment")))
                  (map keyword (keys (dissoc query/fact-columns "value"))))]
    (paging/validate-order-by! columns paging-options)
    (case version
      (:v2 :v3)
      (let [operators (query/fact-operators version)
            [sql & params] (facts-sql operators query)]
        (conj {:results-query (apply vector (jdbc/paged-sql sql paging-options) params)}
              (when (:count? paging-options)
                [:count-query (apply vector (jdbc/count-sql sql) params)])))
      (qe/compile-user-query->sql
        qe/facts-query query paging-options))))

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
