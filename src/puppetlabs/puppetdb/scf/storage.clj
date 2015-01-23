(ns puppetlabs.puppetdb.scf.storage
  "Catalog persistence

  Catalogs are persisted in a relational database. Roughly speaking,
  the schema looks like this:

  * resource_parameters are associated 0 to N catalog_resources (they are
  deduped across catalogs). It's possible for a resource_param to exist in the
  database, yet not be associated with a catalog. This is done as a
  performance optimization.

  * edges are associated with a single catalog

  * catalogs are associated with a single certname

  * facts are associated with a single certname

  The standard set of operations on information in the database will
  likely result in dangling resources and catalogs; to clean these
  up, it's important to run `garbage-collect!`."
  (:require [puppetlabs.puppetdb.catalogs :as cat]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.data :as data]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.scf.hash-debug :as hashdbg]
            [schema.core :as s]
            [schema.macros :as sm]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [ago secs now before?]]
            [metrics.counters :refer [counter inc! value]]
            [metrics.gauges :refer [gauge]]
            [metrics.histograms :refer [histogram update!]]
            [metrics.timers :refer [timer time!]]
            [puppetlabs.puppetdb.jdbc :refer [query-to-vec dashes->underscores]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def resource-ref-schema
  {:type String
   :title String})

(def json-primitive-schema (s/either String Number Boolean))

(def resource-schema
  (merge resource-ref-schema
         {(s/optional-key :exported) Boolean
          (s/optional-key :file) String
          (s/optional-key :line) s/Int
          (s/optional-key :tags) #{String}
          (s/optional-key :aliases)#{String}
          (s/optional-key :parameters) {s/Any s/Any}}))

(def resource-ref->resource-schema
  {resource-ref-schema resource-schema})

(def edge-relationship-schema (s/enum :contains :before :required-by :notifies :subscription-of))

(def edge-schema
  {:source resource-ref-schema
   :target resource-ref-schema
   :relationship edge-relationship-schema})

(def catalog-schema
  "This is a bit of a hack to make a more restrictive schema in the storage layer.
  Moving the more restrictive resource/edge schemas into puppetdb.catalogs is TODO. Upstream
  code needs to assume a map of resources (not a vector) and tests need to be update to adhere
  to the new format."
  (assoc (cat/catalog-wireformat :all)
    :resources resource-ref->resource-schema
    :edges #{edge-schema}))

(def facts-schema
  {:name String
   :values facts/fact-set
   :timestamp pls/Timestamp
   :environment (s/maybe s/Str)
   :producer_timestamp (s/either (s/maybe s/Str) pls/Timestamp)})

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

(def environments-schema
  {:id s/Int
   :name s/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas - Internal

(def resource-ref->hash {resource-ref-schema String})

(def edge-db-schema
  #{[(s/one String "source hash")
     (s/one String "target hash")
     (s/one String "relationship type")]})

(declare add-certname!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metrics

;; This is pinned to the old namespace for backwards compatibility
(def ns-str "puppetlabs.puppetdb.scf.storage")

;; ## Performance metrics
;;
;; ### Timers for catalog storage
;;
;; * `:replace-catalog`: the time it takes to replace the catalog for
;;   a host
;;
;; * `:new-catalog`: the time it takes to persist a catalog for a
;;    never before seen certname
;;
;; * `:catalog-hash-match`: the time it takes to persist the updates
;;    for a catalog with a matches the previously stored hash
;;
;; * `:catalog-hash-miss`: the time it takes to persist the
;;    differential updates of the previously stored catalog and this one
;;
;; * `:add-resources`: the time it takes to persist just a catalog's
;;   resources
;;
;; * `:add-edges`: the time it takes to persist just a catalog's edges
;;
;; * `:catalog-hash`: the time it takes to compute a catalog's
;;   similary hash
;;
;; ### Counters for catalog storage
;;
;; * `:updated-catalog`: how many brand new or updated (non-duplicate)
;;    catalogs we've received
;;
;; * `:duplicate-catalog`: how many duplicate catalogs we've received
;;
;; ### Gauges for catalog storage
;;
;; * `:duplicate-pct`: percentage of incoming catalogs determined to
;;   be duplicates
;;
;; ### Catalog Histograms
;;
;; * `:catalog-volatility`: number of inserts/updates/deletes required
;;   per hash miss
;;
;; ### Timers for garbage collection
;;
;; * `:gc`: the time it takes to collect all database garbage
;;
;; * `:gc-catalogs`: the time it takes to remove all unused catalogs
;;
;; * `:gc-params`: the time it takes to remove all unused resource params
;;
;; ### Timers for fact storage
;;
;; * `:replace-facts`: the time it takes to replace the facts for a
;;   host
;;
(def metrics
  {
   :add-resources      (timer [ns-str "default" "add-resources"])
   :add-edges          (timer [ns-str "default" "add-edges"])

   :resource-hashes    (timer [ns-str "default" "resource-hashes"])
   :catalog-hash       (timer [ns-str "default" "catalog-hash"])
   :add-new-catalog    (timer [ns-str "default" "new-catalog-time"])
   :catalog-hash-match (timer [ns-str "default" "catalog-hash-match-time"])
   :catalog-hash-miss  (timer [ns-str "default" "catalog-hash-miss-time"])
   :replace-catalog    (timer [ns-str "default" "replace-catalog-time"])

   :gc                 (timer [ns-str "default" "gc-time"])
   :gc-catalogs        (timer [ns-str "default" "gc-catalogs-time"])
   :gc-params          (timer [ns-str "default" "gc-params-time"])
   :gc-environments    (timer [ns-str "default" "gc-environments-time"])
   :gc-report-statuses (timer [ns-str "default" "gc-report-statuses"])
   :gc-fact-paths  (timer  [ns-str "default" "gc-fact-paths"])

   :updated-catalog    (counter [ns-str "default" "new-catalogs"])
   :duplicate-catalog  (counter [ns-str "default" "duplicate-catalogs"])
   :duplicate-pct      (gauge [ns-str "default" "duplicate-pct"]
                              (let [dupes (value (:duplicate-catalog metrics))
                                    new   (value (:updated-catalog metrics))]
                                (float (kitchensink/quotient dupes (+ dupes new)))))
   :catalog-volatility (histogram [ns-str "default" "catalog-volitilty"])

   :replace-facts     (timer [ns-str "default" "replace-facts-time"])

   :store-report      (timer [ns-str "default" "store-report-time"])
   })

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Certname querying/deleting

(defn certname-exists?
  "Returns a boolean indicating whether or not the given certname exists in the db"
  [certname]
  {:pre [certname]}
  (sql/with-query-results result-set
    ["SELECT 1 FROM certnames WHERE name=? LIMIT 1" certname]
    (pos? (count result-set))))

(defn delete-certname!
  "Delete the given host from the db"
  [certname]
  {:pre [certname]}
  (sql/delete-rows :certnames ["name=?" certname]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Node activation/deactivation

(defn stale-nodes
  "Return a list of nodes that have seen no activity between
  (now-`time` and now)"
  [time]
  {:pre  [(kitchensink/datetime? time)]
   :post [(coll? %)]}
  (let [ts (to-timestamp time)]
    (map :name (jdbc/query-to-vec "SELECT c.name FROM certnames c
                                   LEFT OUTER JOIN catalogs clogs ON c.name=clogs.certname
                                   LEFT OUTER JOIN factsets fs ON c.name=fs.certname
                                   WHERE c.deactivated IS NULL
                                   AND (clogs.timestamp IS NULL OR clogs.timestamp < ?)
                                   AND (fs.timestamp IS NULL OR fs.timestamp < ?)"
                                  ts ts))))

(defn node-deactivated-time
  "Returns the time the node specified by `certname` was deactivated, or nil if
  the node is currently active."
  [certname]
  {:pre [(string? certname)]}
  (sql/with-query-results result-set
    ["SELECT deactivated FROM certnames WHERE name=?" certname]
    (:deactivated (first result-set))))

(defn purge-deactivated-nodes!
  "Delete nodes from the database which were deactivated before `time`."
  [time]
  {:pre [(kitchensink/datetime? time)]}
  (let [ts (to-timestamp time)]
    (sql/delete-rows :certnames ["deactivated < ?" ts])))

(defn activate-node!
  "Reactivate the given host.  Adds the host to the database if it was not
  already present."
  [certname]
  {:pre [(string? certname)]}
  (when-not (certname-exists? certname)
    (add-certname! certname))
  (sql/update-values :certnames
                     ["name=?" certname]
                     {:deactivated nil}))


(pls/defn-validated create-row :- s/Int
  "Creates a row using `row-map` for `table`, returning the PK that was created upon insert"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (:id (first (sql/insert-records table row-map))))

(pls/defn-validated query-id :- (s/maybe s/Int)
  "Returns the id (primary key) from `table` that contain `row-map` values"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (let [cols (keys row-map)
        where-clause (str "where " (str/join " " (map (fn [col] (str (name col) "=?") ) cols)))]
    (sql/with-query-results rs (apply vector (format "select id from %s %s" (name table) where-clause) (map row-map cols))
      (:id (first rs)))))

(pls/defn-validated ensure-row :- (s/maybe s/Int)
  "Check if the given row (defined by `row-map` exists in `table`, creates it if it does not. Always returns
   the id of the row (whether created or existing)"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (when row-map
    (if-let [id (query-id table row-map)]
      id
      (create-row table row-map))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Environments querying/updating

(pls/defn-validated environment-id :- (s/maybe s/Int)
  "Returns the id (primary key) from the environments table for the given `env-name`"
  [env-name :- s/Str]
  (query-id :environments {:name env-name}))

(pls/defn-validated ensure-environment :- (s/maybe s/Int)
  "Check if the given `env-name` exists, creates it if it does not. Always returns
   the id of the `env-name` (whether created or existing)"
  [env-name :- (s/maybe s/Str)]
  (when env-name
    (ensure-row :environments {:name env-name})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Status querying/updating

(pls/defn-validated status-id :- (s/maybe s/Int)
  "Returns the id (primary key) from the result_statuses table for the given `status`"
  [status :- s/Str]
  (query-id :report_statuses {:status status}))

(pls/defn-validated ensure-status :- (s/maybe s/Int)
  "Check if the given `status` exists, creates it if it does not. Always returns
   the id of the `status` (whether created or existing)"
  [status :- (s/maybe s/Str)]
  (when status
    (ensure-row :report_statuses {:status status})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog updates/changes

(defn catalog-metadata
  "Returns the id and hash of certname's catalog"
  [certname]
  {:pre [certname]}
  (sql/with-query-results result-set
    ["SELECT id, hash FROM catalogs WHERE certname=?" certname]
    (first result-set)))

(pls/defn-validated catalog-row-map
  "Creates a row map for the catalogs table, optionally adding envrionment when it was found"
  [hash
   {:keys [api_version version transaction_uuid environment producer_timestamp]} :- catalog-schema
   timestamp :- pls/Timestamp]
  {:hash hash
   :api_version api_version
   :catalog_version  version
   :transaction_uuid transaction_uuid
   :timestamp (to-timestamp timestamp)
   :environment_id (ensure-environment environment)
   :producer_timestamp (to-timestamp producer_timestamp)})

(pls/defn-validated update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [id :- Number
   hash :- String
   catalog :- catalog-schema
   timestamp :- pls/Timestamp]
  (sql/update-values :catalogs
                     ["id=?" id]
                     (catalog-row-map hash catalog timestamp)))

(pls/defn-validated add-catalog-metadata!
  "Given some catalog metadata, persist it in the db. Returns a map of the
  inserted data including any autogenerated columns."
  [hash :- String
   {:keys [name] :as catalog} :- catalog-schema
   timestamp :- pls/Timestamp]
  {:post [(map? %)]}
  (first (sql/insert-records :catalogs
                             (assoc (catalog-row-map hash catalog timestamp)
                               :certname name))))

(pls/defn-validated resources-exist? :- #{String}
  "Given a collection of resource-hashes, return the subset that
  already exist in the database."
  [resource-hashes :- #{String}]
  {:pre  [(coll? resource-hashes)
          (every? string? resource-hashes)]
   :post [(set? %)]}
  (let [qmarks     (str/join "," (repeat (count resource-hashes) "?"))
        query      (format "SELECT DISTINCT resource FROM resource_params_cache WHERE resource IN (%s)" qmarks)
        sql-params (vec (cons query resource-hashes))]
    (sql/with-query-results result-set
      sql-params
      (set (map :resource result-set)))))

;;The schema definition of this function should be
;;resource-ref->resource-schema, but there are a lot of tests that
;;have incorrect data. When examples.clj and tests get fixed, this
;;should be changed to the correct schema
(pls/defn-validated catalog-resources
  "Returns the resource hashes keyed by resource reference"
  [catalog-id :- Number]
  (sql/with-query-results result-set
    ["SELECT type, title, tags, exported, file, line, resource
      FROM catalog_resources
      WHERE catalog_id = ?" catalog-id]
    (zipmap (map #(select-keys % [:type :title]) result-set)
            (jdbc/convert-result-arrays set result-set))))

(pls/defn-validated new-params-only
  "Returns a map of not persisted parameters, keyed by hash"
  [persisted-params :- #{String}
   refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- resource-ref->hash]
  (reduce-kv (fn [acc resource-ref {:keys [parameters]}]
               (let [resource-hash (get refs-to-hashes resource-ref)]
                 (if (contains? persisted-params (refs-to-hashes resource-ref))
                   acc
                   (assoc acc resource-hash parameters))))
             {} refs-to-resources))

(pls/defn-validated insert-records*
  "Nil/empty safe insert-records, see java.jdbc's insert-records for more "
  [table :- s/Keyword
   record-coll :- [{s/Keyword s/Any}]]
  (when (seq record-coll)
    (apply sql/insert-records table record-coll)))

(pls/defn-validated add-params!
  "Persists the new parameters found in `refs-to-resources` and populates the
   resource_params_cache."
  [refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- resource-ref->hash]
  (let [new-params (new-params-only (resources-exist? (kitchensink/valset refs-to-hashes))
                                    refs-to-resources
                                    refs-to-hashes)]

    (update! (:catalog-volatility metrics) (* 2 (count new-params)))

    (insert-records*
     :resource_params_cache
     (map (fn [[resource-hash params]]
            {:resource resource-hash :parameters (when params (sutils/db-serialize params))})
          new-params))

    (insert-records*
     :resource_params
     (for [[resource-hash params] new-params
           [k v] params]
       {:resource resource-hash :name (name k) :value (sutils/db-serialize v)}))))

(def resource-ref?
  "Returns true of the map is a resource reference"
  (every-pred :type :title))

(defn convert-tags-array
  "Converts the given tags (if present) to the format the database expects"
  [resource]
  (if (contains? resource :tags)
    (update-in resource [:tags] sutils/to-jdbc-varchar-array)
    resource))

(pls/defn-validated insert-catalog-resources!
  "Returns a function that accepts a seq of ref keys to insert"
  [catalog-id :- Number
   refs-to-hashes :- {resource-ref-schema String}
   refs-to-resources :- resource-ref->resource-schema]
  (fn [refs-to-insert]
    {:pre [(every? resource-ref? refs-to-insert)]}

    (update! (:catalog-volatility metrics) (count refs-to-insert))

    (insert-records*
     :catalog_resources
     (map (fn [resource-ref]
            (let [{:keys [type title exported parameters tags file line] :as resource} (get refs-to-resources resource-ref)]
              (convert-tags-array
               {:catalog_id catalog-id
                :resource (get refs-to-hashes resource-ref)
                :type type
                :title title
                :tags tags
                :exported exported
                :file file
                :line line})))
          refs-to-insert))))

(pls/defn-validated delete-catalog-resources!
  "Returns a function accepts old catalog resources that should be deleted."
  [catalog-id :- Number]
  (fn [refs-to-delete]
    {:pre [(every? resource-ref? refs-to-delete)]}

    (update! (:catalog-volatility metrics) (count refs-to-delete))

    (doseq [{:keys [type title]} refs-to-delete]
      (sql/delete-rows :catalog_resources ["catalog_id = ? and type = ? and title = ?" catalog-id type title]))))

(sm/defn basic-diff
  "Basic diffing that returns only the keys/values of `right` whose values don't match those of `left`.
   This is different from clojure.data/diff in that it treats non-equal sets as completely different
   (rather than returning only the differing items of the set) and only returns differences from `right`."
  [left right]
  (reduce-kv (fn [acc k right-value]
               (let [left-value (get left k)]
                 (if (= left-value right-value)
                   acc
                   (assoc acc k right-value))))
             {} right))

(sm/defn diff-resources-metadata
  "Return resource references with values that are only the key/values that from `right` that
   are different from those of the `left`. The keys/values here are suitable for issuing update
   statements that will update resources to the correct (new) values."
  [left right]
  (reduce-kv (fn [acc k right-values]
               (let [updated-resource-vals (basic-diff (get left k) right-values)]
                 (if (seq updated-resource-vals)
                   (assoc acc k updated-resource-vals)
                   acc))) {} right))

(defn merge-resource-hash
  "Assoc each hash from `refs-to-hashes` as :resource on `refs-to-resources`"
  [refs-to-hashes refs-to-resources]
  (reduce-kv (fn [acc k v]
               (assoc-in acc [k :resource] (get refs-to-hashes k)))
             refs-to-resources refs-to-resources))

(pls/defn-validated update-catalog-resources!
  "Returns a function accepting keys that were the same from the old resources and the new resources."
  [catalog-id :- Number
   refs-to-hashes :- {resource-ref-schema String}
   refs-to-resources
   old-resources]

  (fn [maybe-updated-refs]
    {:pre [(every? resource-ref? maybe-updated-refs)]}
    (let [new-resources-with-hash (merge-resource-hash refs-to-hashes (select-keys refs-to-resources maybe-updated-refs))
          updated-resources (diff-resources-metadata old-resources new-resources-with-hash)]

      (update! (:catalog-volatility metrics) (count updated-resources))

      (doseq [[{:keys [type title]} updated-cols] updated-resources]
        (sql/update-values :catalog_resources
                           ["catalog_id = ? and type = ? and title = ?" catalog-id type title]
                           (convert-tags-array updated-cols))))))

(defn strip-params
  "Remove params from the resource as it is stored (and hashed) separately
  from the resource metadata"
  [resource]
  (dissoc resource :parameters))

(pls/defn-validated add-resources!
  "Persist the given resource and associate it with the given catalog."
  [catalog-id :- Number
   refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (let [old-resources (catalog-resources catalog-id)
        diffable-resources (kitchensink/mapvals strip-params refs-to-resources)]
    (sql/transaction
     (add-params! refs-to-resources refs-to-hashes)
     (utils/diff-fn old-resources
                    diffable-resources
                    (delete-catalog-resources! catalog-id)
                    (insert-catalog-resources! catalog-id refs-to-hashes diffable-resources)
                    (update-catalog-resources! catalog-id refs-to-hashes diffable-resources old-resources)))))

(pls/defn-validated catalog-edges-map
  "Return all edges for a given catalog id as a map"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT source, target, type FROM edges WHERE certname=?" certname]
    ;; Transform the result-set into a map with [source,target,type] as the key
    ;; and nil as always the value. This just feeds into clojure.data/diff
    ;; better this way.
    (zipmap (map vals result-set)
            (repeat nil))))

(pls/defn-validated delete-edges!
  "Delete edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, eg:

    [[<source> <target> <type>] ...]"
  [certname :- String
   edges :- edge-db-schema]

  (update! (:catalog-volatility metrics) (count edges))

  (doseq [[source target type] edges]
    ;; This is relatively inefficient. If we have id's for edges, we could do
    ;; this in 1 statement.
    (sql/delete-rows :edges
                     ["certname=? and source=? and target=? and type=?" certname source target type])))

(pls/defn-validated insert-edges!
  "Insert edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, eg:

    [[<source> <target> <type>] ...]"
  [certname :- String
   edges :- edge-db-schema]

  ;; Insert rows will not safely accept a nil, so abandon this operation
  ;; earlier.
  (when (seq edges)
    (let [rows (for [[source target type] edges]
                 [certname source target type])]

      (update! (:catalog-volatility metrics) (count rows))
      (apply sql/insert-rows :edges rows))))

(pls/defn-validated replace-edges!
  "Persist the given edges in the database

  Each edge is looked up in the supplied resources map to find a
  resource object that corresponds to the edge. We then use that
  resource's hash for persistence purposes.

  For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
  then we'll lookup a resource with that key and use its hash."
  [certname :- String
   edges :- #{edge-schema}
   refs-to-hashes :- {resource-ref-schema String}]

  (let [new-edges (zipmap
                   (for [{:keys [source target relationship]} edges
                         :let [source-hash (refs-to-hashes source)
                               target-hash (refs-to-hashes target)
                               type        (name relationship)]]
                     [source-hash target-hash type])
                   (repeat nil))]
    (utils/diff-fn new-edges
                   (catalog-edges-map certname)
                   #(insert-edges! certname %)
                   #(delete-edges! certname %)
                   identity)))

(pls/defn-validated update-catalog-hash-match
  "When a new incoming catalog has the same hash as an existing catalog, update metrics
   and the transaction id for the new catalog"
  [catalog-id :- Number
   hash :- String
   catalog :- catalog-schema
   timestamp :- pls/Timestamp]
  (inc! (:duplicate-catalog metrics))
  (time! (:catalog-hash-match metrics)
         (update-catalog-metadata! catalog-id hash catalog timestamp)))

(pls/defn-validated update-catalog-associations!
  "Adds/updates/deletes the edges and resources for the given certname"
  [catalog-id :- Number
   {:keys [resources edges name]} :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (time! (:add-resources metrics)
         (add-resources! catalog-id resources refs-to-hashes))
  (time! (:add-edges metrics)
         (replace-edges! name edges refs-to-hashes)))

(pls/defn-validated update-catalog-hash-miss
  "New catalogs for a given certname needs to have their metadata, resources and edges updated.  This
   function also outputs debugging related information when `catalog-hash-debug-dir` is not nil"
  [catalog-id :- Number
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   catalog-hash-debug-dir :- (s/maybe s/Str)
   timestamp :- pls/Timestamp]

  (inc! (:updated-catalog metrics))

  (when catalog-hash-debug-dir
    (hashdbg/debug-catalog catalog-hash-debug-dir hash catalog))

  (time! (:catalog-hash-miss metrics)
         (update-catalog-metadata! catalog-id hash catalog timestamp)
         (update-catalog-associations! catalog-id catalog refs-to-hashes)))

(pls/defn-validated add-new-catalog
  "Creates new catalog metadata and adds the proper associations for the edges and resources"
  [hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   timestamp :- pls/Timestamp]
  (inc! (:updated-catalog metrics))
  (time! (:add-new-catalog metrics)
         (let [catalog-id (:id (add-catalog-metadata! hash catalog timestamp))]
           (update-catalog-associations! catalog-id catalog refs-to-hashes))))

(pls/defn-validated add-catalog!
  "Persist the supplied catalog in the database, returning its
   similarity hash. `catalog-hash-debug-dir` is an optional path that
   indicates where catalog debugging information should be stored."
  ([catalog :- catalog-schema]
   (add-catalog! catalog nil (now)))
  ([{:keys [api_version resources edges name] :as catalog} :- catalog-schema
    catalog-hash-debug-dir :- (s/maybe s/Str)
    timestamp :- pls/Timestamp]

   (let [refs-to-hashes (time! (:resource-hashes metrics)
                               (reduce-kv (fn [acc k v]
                                            (assoc acc k (shash/resource-identity-hash v)))
                                          {} resources))
         hash           (time! (:catalog-hash metrics)
                               (shash/catalog-similarity-hash catalog))
         {id :id
          stored-hash :hash} (catalog-metadata name)]

     (sql/transaction
      (cond
       (nil? id)
       (add-new-catalog hash catalog refs-to-hashes timestamp)

       (= stored-hash hash)
       (update-catalog-hash-match id hash catalog timestamp)

       :else
       (update-catalog-hash-miss id hash catalog refs-to-hashes catalog-hash-debug-dir timestamp)))

     hash)))

(defn delete-catalog!
  "Remove the catalog identified by the following hash"
  [catalog-hash]
  (sql/delete-rows :catalogs ["hash=?" catalog-hash]))

(defn catalog-hash-for-certname
  "Returns the hash for the `certname` catalog"
  [certname]
  (sql/with-query-results result-set
    ["SELECT hash as catalog FROM catalogs WHERE certname=?" certname]
    (:catalog (first result-set))))

;; ## Database compaction

(defn delete-unassociated-params!
  "Remove any resources that aren't associated with a catalog"
  []
  (time! (:gc-params metrics)
         (sql/delete-rows :resource_params_cache ["NOT EXISTS (SELECT * FROM catalog_resources cr WHERE cr.resource=resource_params_cache.resource)"])))


(defn delete-unassociated-environments!
  "Remove any environments that aren't associated with a catalog, report or factset"
  []
  (time! (:gc-environments metrics)
         (sql/delete-rows :environments
                          ["ID NOT IN
              (SELECT environment_id FROM catalogs WHERE environment_id IS NOT NULL
               UNION
               SELECT environment_id FROM reports WHERE environment_id IS NOT NULL
               UNION
               SELECT environment_id FROM factsets WHERE environment_id IS NOT NULL)"])))

(defn delete-unassociated-statuses!
  "Remove any statuses that aren't associated with a report"
  []
  (time! (:gc-report-statuses metrics)
         (sql/delete-rows :report_statuses
                          ["ID NOT IN (SELECT status_id FROM reports)"])))

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  []
  (time! (:gc metrics)
         (sql/transaction
          (delete-unassociated-params!))
         (sql/transaction
          (delete-unassociated-environments!))
         (sql/transaction
          (delete-unassociated-statuses!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Facts

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
      (vec (flatten [(str "SELECT fp.id, fp.path, fp.depth, fp.value_type_id, fp.name FROM fact_paths fp WHERE (fp.path, fp.depth, fp.value_type_id, fp.name)"
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
      (vec (flatten [(str "SELECT fv.id, fv.value_type_id, fv.value_hash, fv.path_id FROM fact_values fv WHERE (fv.path_id, fv.value_type_id, fv.value_hash) "
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
  (let [record-set (mapv
                    #(select-keys % [:path_id :value_type_id :value_hash
                                     :value_string :value_json :value_integer
                                     :value_float :value_boolean])
                    factvalues)
        ;; Here we merge the results with the record set to make the hsqldb
        ;; driver work more like pgsql.
        result-set (map-indexed (fn [idx itm] (merge (get record-set idx) itm))
                                (apply sql/insert-records :fact_values record-set))]
    (into {} (map (fn [data]
                    [(select-keys data [:path_id :value_type_id :value_hash
                                        :value_string :value_json :value_integer
                                        :value_float :value_boolean])
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
  [fact-path-maps :- [facts/fact-path-map]]
  (let [factpaths (map #(select-keys % [:path :depth :value_type_id :name])
                       fact-path-maps)
        paths-to-id (fact-paths-to-ids factpaths)
        ;; New path map with path_id's set
        fact-path-maps (map (fn [path-map]
                              (let [path-id (get paths-to-id
                                                 (select-keys path-map [:path :depth
                                                                        :name
                                                                        :value_type_id]))]
                                (assoc path-map :path_id path-id)))
                            fact-path-maps)

        ;; List of maps with value :path-id and :value
        factvalues (map #(select-keys % [:path_id :value_string :value_json :value_integer :value_float :value_boolean :value_hash :value_type_id])
                        fact-path-maps)

        values-to-id (fact-values-to-ids factvalues)]
    (vals values-to-id)))

(pls/defn-validated new-fact-value-ids :- [s/Int]
  "Given a fact hash, return a list of value ids.

  This function will create new fact_paths and fact_values as necessary to
  return a full set of ids to the caller."
  [facts :- facts/fact-set]
  (let [path-maps (facts/factmap-to-paths facts)]
    (new-fact-value-ids* path-maps)))

(pls/defn-validated current-fact-value-ids :- [s/Int]
  "Return a list of fact value ids for a given factset"
  [factset :- s/Int]
  (sql/with-query-results result-set
    ["SELECT fact_value_id FROM facts WHERE factset_id = ?" factset]
    (mapv :fact_value_id result-set)))

(pls/defn-validated certname-to-factset-id :- s/Int
  "Given a certname, returns the factset id."
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT id from factsets WHERE certname = ?" certname]
    (:id (first result-set))))

(pls/defn-validated factset-map :- {s/Str s/Str}
  "Return all facts and their values for a given certname as a map"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT fp.path as name,
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
                   fs.certname = ?"
     certname]
    (zipmap (map :name result-set)
            (map :value result-set))))

(pls/defn-validated insert-facts!
  "Given a certname and set of fact_value_id's, insert them into the facts table"
  [factset :- s/Int
   ids     :- #{s/Int}]
  (let [default {:factset_id factset}
        rows    (map #(assoc default :fact_value_id %)
                     ids)]
    (apply sql/insert-records :facts rows)))

(pls/defn-validated add-facts!
  "Given a certname and a map of fact names to values, store records for those
   facts associated with the certname."
  ([fact-data]
     (add-facts! fact-data true))
  ([{:keys [name values environment timestamp producer_timestamp] :as fact-data} :- facts-schema
    include-hash? :- s/Bool]
     (let [factset {:certname name
                    :timestamp (to-timestamp timestamp)
                    :environment_id (ensure-environment environment)
                    :producer_timestamp (to-timestamp producer_timestamp)}
           fact-data-hash (shash/generic-identity-hash (dissoc fact-data :timestamp :producer_timestamp))]
       (sql/insert-record :factsets
                          (if include-hash?
                            (assoc factset :hash fact-data-hash) factset))
       (insert-facts!
        (certname-to-factset-id name)
        (set (new-fact-value-ids values))))))

(pls/defn-validated delete-facts!
  "Delete all the facts (1 arg) or just the fact-ids (2 args) for the given certname."
  ([certname :- String]
   (let [factset-value-ids (query-to-vec ["select id, fact_value_id from factsets fs inner join facts f on fs.id = f.factset_id where fs.certname = ?" certname])
         factset-id (:id (first factset-value-ids))]
     (delete-facts! factset-id (set (map :fact_value_id factset-value-ids)))
     (sql/delete-rows :factsets ["id=?" factset-id])))
  ([factset :- s/Int
    fact-ids :- #{s/Int}]
   (when (seq fact-ids)
     (let [in-list (jdbc/in-clause fact-ids)]
       (sql/transaction

        (when (sutils/postgres?)
          (sql/do-commands "SET CONSTRAINTS ALL DEFERRED")

          (sql/do-prepared (format "DELETE FROM fact_paths fp
                                      WHERE fp.id in ( SELECT fp.id
                                                       FROM fact_paths fp
                                                            inner join fact_values fv on fp.id = fv.path_id
                                                            inner join facts f on fv.id = f.fact_value_id
                                                       WHERE fp.id in ( select fv.path_id from fact_values fv where fv.id %s )
                                                       GROUP BY fp.id
                                                       HAVING COUNT(fv.id) = 1)" in-list)
                           fact-ids)

          (sql/do-prepared (format "DELETE FROM fact_values WHERE id in (
                                          SELECT fact_value_id FROM facts where factset_id = ? and fact_value_id %s
                                          EXCEPT
                                          SELECT fact_value_id FROM facts where factset_id <> ? and fact_value_id %s)" in-list in-list)
                           (concat (cons factset fact-ids)
                                   (cons factset fact-ids))))

        (sql/delete-rows :facts
                         (into [(str "factset_id=? and fact_value_id "
                                     in-list) factset]
                               fact-ids))

        ;; HSQLDB doesn't support delayed constraints. This query
        ;; achieves the same goals as the DELETE FROM fact_values
        ;; above, but does so in a less efficient manner. Having
        ;; these two separated allows Postgres queries to execute
        ;; faster, but still support HSQLDB
        (when-not (sutils/postgres?)
          (sql/do-prepared (format "DELETE FROM fact_values WHERE id in (
                                        SELECT fv.id
                                        FROM fact_values fv left join facts f on fv.id = f.fact_value_id
                                        WHERE f.fact_value_id is NULL and fv.id %s )" in-list)
                           fact-ids)
          (sql/do-prepared (format "DELETE FROM fact_paths fp
                                      WHERE fp.id in ( SELECT fp.id
                                                       FROM fact_paths fp left join fact_values fv on fp.id = fv.path_id
                                                       WHERE fv.path_id is NULL )"))))))))

(pls/defn-validated update-facts!
  "Given a certname, querys the DB for existing facts for that
  certname and will update, delete or insert the facts as necessary
  to match the facts argument."
  [{:keys [name values environment timestamp producer_timestamp] :as fact-data} :- facts-schema]
  (let [factset-id (certname-to-factset-id name)
        old-facts (current-fact-value-ids factset-id)
        new-facts (new-fact-value-ids values)
        factset {:timestamp (to-timestamp timestamp)
                 :environment_id (ensure-environment environment)
                 :producer_timestamp (to-timestamp producer_timestamp)
                 :hash (shash/generic-identity-hash (dissoc fact-data :producer_timestamp :timestamp))}]
    (sql/update-values :factsets ["id=?" factset-id] factset)
    (utils/diff-fn (zipmap new-facts (repeat nil))
                   (zipmap old-facts (repeat nil))
                   #(insert-facts! factset-id %)
                   #(delete-facts! factset-id %)
                   identity)))

(pls/defn-validated factset-timestamp :- (s/maybe pls/Timestamp)
  "Return the factset timestamp for the given certname, nil if not found"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT timestamp FROM factsets WHERE certname=? ORDER BY timestamp DESC" certname]
    (:timestamp (first result-set))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reports

(defn update-latest-report!
  "Given a node name, updates the `latest_reports` table to ensure that it indicates the
  most recent report for the node."
  [node]
  {:pre [(string? node)]}
  (let [latest-report (:hash (first (query-to-vec
                                     ["SELECT hash FROM reports
                                            WHERE certname = ?
                                            ORDER BY end_time DESC
                                            LIMIT 1" node])))]
    (sql/update-or-insert-values
     :latest_reports
     ["certname = ?" node]
     {:certname      node
      :report        latest-report})))

(defn find-containing-class
  "Given a containment path from Puppet, find the outermost 'class'."
  [containment-path]
  {:pre [(or
          (nil? containment-path)
          (and (coll? containment-path) (every? string? containment-path)))]
   :post [((some-fn nil? string?) %)]}
  (when-not ((some-fn nil? empty?) containment-path)
    ;; This is a little wonky.  Puppet only gives us an array of Strings
    ;; to represent the containment path.  Classes can be differentiated
    ;; from types because types have square brackets and a title; so, e.g.,
    ;; "Foo" is a class, but "Foo[Bar]" is a type with a title.
    (first
     (filter
      #(not (or (empty? %) (kitchensink/string-contains? "[" %)))
      (reverse containment-path)))))

(defn maybe-environment
  "This fn is most to help in testing, instead of persisting a value of
  nil, just omit it from the row map. For tests that are running older versions
  of migrations, this function prevents a failure"
  [row-map]
  (if (nil? (:environment_id row-map))
    (dissoc row-map :environment_id)
    row-map))

(defn add-report!*
  "Helper function for adding a report.  Accepts an extra parameter, `update-latest-report?`, which
  is used to determine whether or not the `update-latest-report!` function will be called as part of
  the transaction.  This should always be set to `true`, except during some very specific testing
  scenarios."
  [{:keys [puppet_version certname report_format configuration_version
           start_time end_time resource_events transaction_uuid environment
           status]
    :as report}
   timestamp
   update-latest-report?]
  {:pre [(map? report)
         (kitchensink/datetime? timestamp)
         (kitchensink/boolean? update-latest-report?)]}
  (let [report-hash         (shash/report-identity-hash report)
        containment-path-fn (fn [cp] (if-not (nil? cp) (sutils/to-jdbc-varchar-array cp)))
        resource-event-rows (map #(-> %
                                      (utils/update-when [:timestamp] to-timestamp)
                                      (utils/update-when [:old_value] sutils/db-serialize)
                                      (utils/update-when [:new_value] sutils/db-serialize)
                                      (utils/update-when [:containment_path] containment-path-fn)
                                      (assoc :containing_class (find-containing-class (% :containment_path)))
                                      (assoc :report report-hash))
                                 resource_events)]
    (time! (:store-report metrics)
           (sql/transaction
            (sql/insert-record :reports
                               (maybe-environment
                                {:hash                   report-hash
                                 :puppet_version         puppet_version
                                 :certname               certname
                                 :report_format          report_format
                                 :configuration_version  configuration_version
                                 :start_time             (to-timestamp start_time)
                                 :end_time               (to-timestamp end_time)
                                 :receive_time           (to-timestamp timestamp)
                                 :transaction_uuid       transaction_uuid
                                 :environment_id         (ensure-environment environment)
                                 :status_id              (ensure-status status)}))

            (apply sql/insert-records :resource_events resource-event-rows)
            (if update-latest-report?
              (update-latest-report! certname))))))

(defn delete-reports-older-than!
  "Delete all reports in the database which have an `end-time` that is prior to
  the specified date/time."
  [time]
  {:pre [(kitchensink/datetime? time)]}
  (sql/delete-rows :reports ["end_time < ?" (to-timestamp time)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database support/deprecation

(defn db-unsupported?
  "Returns a string with an unsupported message if the DB is not supported,
  nil otherwise."
  []
  (when (and (sutils/postgres?)
             (sutils/db-version-older-than? [9 3]))
    "PostgreSQL DB versions 9.2 and older are no longer supported. Please upgrade Postgres and restart PuppetDB."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(pls/defn-validated add-certname!
  "Add the given host to the db"
  [certname :- String]
  (sql/insert-record :certnames {:name certname}))

(pls/defn-validated maybe-activate-node!
  "Reactivate the given host, only if it was deactivated before `time`.
  Returns true if the node is activated, or if it was already active.

  Adds the host to the database if it was not already present."
  [certname :- String
   time :- pls/Timestamp]
  (when-not (certname-exists? certname)
    (add-certname! certname))
  (let [timestamp (to-timestamp time)
        replaced  (sql/update-values :certnames
                                     ["name=? AND (deactivated<? OR deactivated IS NULL)" certname timestamp]
                                     {:deactivated nil})]
    (pos? (first replaced))))

(pls/defn-validated deactivate-node!
  "Deactivate the given host, recording the current time. If the node is
  currently inactive, no change is made."
  [certname :- String]
  (sql/do-prepared "UPDATE certnames SET deactivated = ?
                    WHERE name=? AND deactivated IS NULL"
                   [(to-timestamp (now)) certname]))

(pls/defn-validated catalog-newer-than?
  "Returns true if the most current catalog for `certname` is more recent than
  `time`."
  [certname :- String
   time :- pls/Timestamp]
  (let [timestamp (to-timestamp time)]
    (sql/with-query-results result-set
      ["SELECT timestamp FROM catalogs WHERE certname=? ORDER BY timestamp DESC LIMIT 1" certname]
      (if-let [catalog-timestamp (:timestamp (first result-set))]
        (.after catalog-timestamp timestamp)
        false))))

(pls/defn-validated replace-catalog!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one. `catalog-hash-debug-dir`
  is an optional path that indicates where catalog debugging information
  should be stored."
  ([catalog :- catalog-schema
    timestamp :- pls/Timestamp]
   (replace-catalog! catalog timestamp nil))
  ([{:keys [name] :as catalog} :- catalog-schema
    timestamp :- pls/Timestamp
    catalog-hash-debug-dir :- (s/maybe s/Str)]
   (time! (:replace-catalog metrics)
          (sql/transaction
           (add-catalog! catalog catalog-hash-debug-dir timestamp)))))

(pls/defn-validated replace-facts!
  "Updates the facts of an existing node, if the facts are newer than the current set of facts.
   Adds all new facts if no existing facts are found. Invoking this function under the umbrella of
   a repeatable read or serializable transaction enforces only one update to the facts of a certname
   can happen at a time.  The first to start the transaction wins.  Subsequent transactions will fail
   as the factsets will have changed while the transaction was in-flight."
  [{:keys [name timestamp] :as fact-data} :- facts-schema]
  (time! (:replace-facts metrics)
         (if-let [factset-ts (factset-timestamp name)]
           (when (.before factset-ts (to-timestamp timestamp))
             (update-facts! fact-data))
           (add-facts! fact-data))))

(pls/defn-validated add-report!
  "Add a report and all of the associated events to the database."
  [report
   timestamp :- pls/Timestamp]
  (add-report!* report timestamp true))

(defn fail-on-unsupported
  "Log an error message to the log and console if the currently
  configured database is unsupported, then call fail-fn  (probably to
  exit)."
  [fail-fn]
  (let [msg (db-unsupported?)]
    (when-let [attn-msg (and msg (utils/attention-msg msg))]
      (utils/println-err attn-msg)
      (log/error attn-msg)
      (fail-fn))))

(defn validate-database-version
  "Checks to ensure that the database is supported, fails if supported, logs
  if deprecated"
  [action-for-unsupported-fn]
  (fail-on-unsupported action-for-unsupported-fn))
