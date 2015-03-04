;;;
;;; Vestigial bits we have to keep around for various migrations.
;;; Note that every external call here increases the risk that future
;;; changes might break past migrations.
;;;
(ns com.puppetlabs.puppetdb.scf.migration-legacy
  (:require
   [com.puppetlabs.puppetdb.facts :as facts]
   [com.puppetlabs.jdbc :as jdbc]
   [clojure.java.jdbc :as sql]
   [clojure.string :as str]
   [schema.core :as s]
   [com.puppetlabs.puppetdb.schema :as pls]
   [clj-time.coerce :refer [to-timestamp]]))

;; Note: this still relies on some code from facts, so changes to
;; those functions facts/value-type-id could break the related
;; migration(s).

(def fact-path-map
  {:path s/Str
   :depth s/Int
   :name s/Str
   :value_hash s/Str
   :value_float (s/maybe Double)
   :value_string (s/maybe s/Str)
   :value_integer (s/maybe s/Int)
   :value_boolean (s/maybe s/Bool)
   :value_json (s/maybe s/Str)
   :value_type_id s/Int})

(pls/defn-validated factmap-to-paths :- [fact-path-map]
  "Converts a map of facts to a list of `fact-path-map`s."
  [facts :- facts/fact-set]
  (facts/flatten-facts-with (fn [path leaf]
                              (merge (facts/value->valuemap leaf)
                                     (facts/path->pathmap path)))
                            facts))

(def fact-path-types-to-ids-map
  {:path s/Str
   :name s/Str
   :depth s/Int
   :value_type_id s/Int})

(def fact-values-to-ids-map
  {:path_id s/Int
   :value_type_id s/Int
   :value_hash s/Str
   (s/optional-key :value_float) (s/maybe Double)
   (s/optional-key :value_string) (s/maybe s/Str)
   (s/optional-key :value_integer) (s/maybe s/Int)
   (s/optional-key :value_boolean) (s/maybe s/Bool)
   (s/optional-key :value_json) (s/maybe s/Str)})

(pls/defn-validated create-row :- s/Int
  "Creates a row using `row-map` for `table`, returning the PK that
  was created upon insert"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (:id (first (sql/insert-records table row-map))))

(pls/defn-validated query-id :- (s/maybe s/Int)
  "Returns the id (primary key) from `table` that contain `row-map` values"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (let [cols (keys row-map)
        where-clause (str "where " (str/join " " (map #(str (name %) "=?")
                                                      cols)))]
    (sql/with-query-results rs (apply vector (format "select id from %s %s"
                                                     (name table)
                                                     where-clause)
                                      (map row-map cols))
      (:id (first rs)))))

(pls/defn-validated ensure-row :- (s/maybe s/Int)
  "Check if the given row (defined by `row-map` exists in `table`, creates it
  if it does not. Always returns the id of the row (whether created or
  existing)"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (when row-map
    (if-let [id (query-id table row-map)]
      id
      (create-row table row-map))))

(pls/defn-validated ensure-environment :- (s/maybe s/Int)
  "Check if the given `env-name` exists, creates it if it does
   not. Always returns the id of the `env-name` (whether created or
   existing)"
  [env-name :- (s/maybe s/Str)]
  (when env-name
    (ensure-row :environments {:name env-name})))

(pls/defn-validated fact-path-current-ids :- {fact-path-types-to-ids-map s/Int}
  "Given a list of fact path strings, return a map of paths to ids for existing
   paths."
  [factpaths :- [fact-path-types-to-ids-map]]
  (let [factpath-data (map (fn [data]
                          [(:path data)
                           (:depth data)
                           (:value_type_id data)
                           (:name data)])
                        factpaths)]
    (sql/with-query-results result-set
      (vec (flatten [(str "SELECT fp.id, fp.path, fp.depth, fp.value_type_id, fp.name
                             FROM fact_paths fp
                             WHERE (fp.path, fp.depth, fp.value_type_id, fp.name)"
                          (jdbc/in-clause-multi factpath-data 4))
                     factpath-data]))
      (into {} (map (fn [data]
                      [(select-keys data [:path :depth :value_type_id :name])
                       (:id data)])
                    result-set)))))

(pls/defn-validated fact-path-new-ids :- {fact-path-types-to-ids-map s/Int}
  "Given a list of fact path strings, return a map of paths to ids for newly
   created paths."
  [factpaths :- [fact-path-types-to-ids-map]]
  (let [record-set (map #(select-keys % [:path :depth :value_type_id :name])
                        factpaths)
        ;; Here we merge the results with the record set to make the hsqldb
        ;; driver work more like pgsql.
        result-set (map-indexed (fn [idx itm] (merge (get (vec record-set) idx) itm))
                                (apply sql/insert-records :fact_paths record-set))]
    (into {} (map (fn [data]
                    [(select-keys data [:path :depth :value_type_id :name])
                     (:id data)])
                  result-set))))

(pls/defn-validated fact-paths-to-ids :- {fact-path-types-to-ids-map s/Int}
  "Given a list of fact paths strings, return a map of ids."
  [factpaths :- [fact-path-types-to-ids-map]]
  (let [current-path-to-ids (fact-path-current-ids factpaths)
        comparable-current-ids (zipmap (keys current-path-to-ids)
                                       (repeat nil))
        comparable-incoming-ids (zipmap (map #(select-keys % [:path :depth
                                                              :value_type_id
                                                              :name])
                                             factpaths)
                                        (repeat nil))
        remaining-paths (keys (remove nil?
                                      (second
                                       (clojure.data/diff comparable-current-ids
                                                          comparable-incoming-ids))))
        new-path-to-ids (fact-path-new-ids remaining-paths)]
    (merge current-path-to-ids new-path-to-ids)))

(pls/defn-validated fact-value-current-ids :- {fact-values-to-ids-map s/Int}
  "Given a list of fact-values-to-ids-map constructs, returns a map with the
  fact-values-to-ids-map being the key, and the current corresponding value id
  as the value."
  [factvalues :- [fact-values-to-ids-map]]
  (let [fv-triples (map (fn [data] [(:path_id data)
                                   (:value_type_id data)
                                   (:value_hash data)])
                       factvalues)]
    (sql/with-query-results result-set
      (vec (flatten [(str "SELECT fv.id, fv.value_type_id, fv.value_hash, fv.path_id
                             FROM fact_values fv
                             WHERE (fv.path_id, fv.value_type_id, fv.value_hash) "
                          (jdbc/in-clause-multi fv-triples 3))
                     fv-triples]))
      (into {} (map (fn [data]
                      [(select-keys data [:path_id :value_type_id :value_hash])
                       (:id data)])
                    result-set)))))

(pls/defn-validated fact-value-new-ids :- {fact-values-to-ids-map s/Int}
  "Give a list of fact-values-to-ids-map constructs, returns a map with the
  fact-values-to-ids-map being the key, and any new value id's as the value."
  [factvalues :- [fact-values-to-ids-map]]
  (let [record-set (mapv #(select-keys % [:path_id
                                          :value_type_id :value_hash
                                          :value_string :value_json
                                          :value_integer :value_float
                                          :value_boolean])
                         factvalues)
        ;; Here we merge the results with the record set to make the hsqldb
        ;; driver work more like pgsql.
        result-set (map-indexed (fn [idx itm] (merge (get record-set idx) itm))
                                (apply sql/insert-records :fact_values
                                       record-set))]
    (into {} (map (fn [data]
                    [(select-keys data [:path_id
                                        :value_type_id :value_hash
                                        :value_string :value_json
                                        :value_integer :value_float
                                        :value_boolean])
                     (:id data)])
                  result-set))))

(pls/defn-validated fact-values-to-ids :- {fact-values-to-ids-map s/Int}
  "Given a list of fact value maps, return a map of values to ids."
  [factvalues :- [fact-values-to-ids-map]]
  (let [current-values-to-ids (fact-value-current-ids factvalues)
        comparable-current-ids (zipmap (keys current-values-to-ids)
                                       (repeat nil))
        primary-keys [:path_id :value_type_id :value_hash]
        prepared-factvalues (map (fn [data]
                                   (select-keys data primary-keys))
                                 factvalues)
        comparable-incoming-ids (zipmap prepared-factvalues
                                        (repeat nil))
        lookup-map (into {}
                         (map (fn [data]
                                [(select-keys data primary-keys)
                                 data])
                              factvalues))
        remaining-values (keys (remove nil?
                                       (second
                                        (clojure.data/diff comparable-current-ids
                                                           comparable-incoming-ids))))
        final-new-values (map (fn [data] (get lookup-map data)) remaining-values)
        new-values-to-ids (fact-value-new-ids final-new-values)]
    (merge current-values-to-ids new-values-to-ids)))

(pls/defn-validated new-fact-value-ids* :- [s/Int]
  "Given a flattened list of fact path maps, return a list of value ids."
  [fact-path-maps :- [fact-path-map]]
  (let [factpaths (map #(select-keys % [:path :depth :value_type_id :name])
                       fact-path-maps)
        paths-to-id (fact-paths-to-ids factpaths)
        ;; New path map with path_id's set
        fact-path-maps (map (fn [path-map]
                              (let [path-id (get paths-to-id
                                                 (select-keys
                                                  path-map [:path :depth
                                                            :name
                                                            :value_type_id]))]
                                (assoc path-map :path_id path-id)))
                            fact-path-maps)

        ;; List of maps with value :path-id and :value
        factvalues (map #(select-keys % [:path_id
                                         :value_string :value_json
                                         :value_integer :value_float
                                         :value_boolean :value_hash
                                         :value_type_id])
                        fact-path-maps)

        values-to-id (fact-values-to-ids factvalues)]
    (vals values-to-id)))

(pls/defn-validated new-fact-value-ids :- [s/Int]
  "Given a fact hash, return a list of value ids.
  This function will create new fact_paths and fact_values as
  necessary to return a full set of ids to the caller."
  [facts :- facts/fact-set]
  (let [path-maps (factmap-to-paths facts)]
    (new-fact-value-ids* path-maps)))

(pls/defn-validated certname-to-factset-id :- s/Int
  "Given a certname, returns the factset id."
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT id from factsets WHERE certname = ?" certname]
    (:id (first result-set))))

(pls/defn-validated insert-facts!
  "Given a certname and set of fact_value_id's, insert them into the
  facts table"
  [factset :- s/Int
   ids :- #{s/Int}]
  (let [default {:factset_id factset}
        rows (map #(assoc default :fact_value_id %) ids)]
    (apply sql/insert-records :facts rows)))

(pls/defn-validated old-add-facts!
  "Given a certname and a map of fact names to values, store records for those
  facts associated with the certname."
  [{:keys [name values environment timestamp producer-timestamp] :as fact-data}
   :- facts/facts-schema]
  (sql/insert-record :factsets
                     {:certname name
                      :timestamp (to-timestamp timestamp)
                      :environment_id (ensure-environment environment)
                      :producer_timestamp (to-timestamp producer-timestamp)})
  (let [new-facts (new-fact-value-ids values)
        factset (certname-to-factset-id name)]
    (insert-facts! factset (set new-facts))))
