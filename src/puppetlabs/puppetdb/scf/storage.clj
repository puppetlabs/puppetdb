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
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.facts :as facts :refer [facts-schema]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.kitchensink.core :as ks]
            [com.rpl.specter :as sp]
            [clojure.java.jdbc :as sql]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.data :as data]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.core :refer [ago now]]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [metrics.counters :refer [counter inc! value]]
            [metrics.gauges :refer [gauge-fn]]
            [metrics.histograms :refer [histogram update!]]
            [metrics.timers :refer [timer time!]]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [honeysql.core :as hcore]
            [puppetlabs.i18n.core :refer [trs]])
  (:import [org.postgresql.util PGobject]
           [org.joda.time Period]))

(defn sql-ts-after? [^java.sql.Timestamp x ^java.sql.Timestamp y]
  (.after x y))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def resource-ref-schema
  {:type String
   :title String})

(def json-primitive-schema (s/cond-pre String Number Boolean))

;; the maximum number of parameters pl-jdbc will admit in a prepared statement
;; is 32767. delete-pending-value-id-orphans will create a prepared statement
;; with 5 times the number of invalidated values, so 6000 here keeps us under
;; that and leaves some room.
(def gc-chunksize 6000)

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
  (assoc cat/catalog-wireformat-schema
         :resources resource-ref->resource-schema
         :edges #{edge-schema}))

(def environments-schema
  {:id s/Int
   :environment s/Str})

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

(def storage-metrics-registry (get-in metrics/metrics-registries [:storage :registry]))

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
(def performance-metrics
  {
   :add-resources      (timer storage-metrics-registry ["add-resources"])
   :add-edges          (timer storage-metrics-registry ["add-edges"])

   :resource-hashes    (timer storage-metrics-registry ["resource-hashes"])
   :catalog-hash       (timer storage-metrics-registry ["catalog-hash"])
   :add-new-catalog    (timer storage-metrics-registry ["new-catalog-time"])
   :catalog-hash-match (timer storage-metrics-registry ["catalog-hash-match-time"])
   :catalog-hash-miss  (timer storage-metrics-registry ["catalog-hash-miss-time"])
   :replace-catalog    (timer storage-metrics-registry ["replace-catalog-time"])

   :gc                 (timer storage-metrics-registry ["gc-time"])
   :gc-catalogs        (timer storage-metrics-registry ["gc-catalogs-time"])
   :gc-params          (timer storage-metrics-registry ["gc-params-time"])
   :gc-environments    (timer storage-metrics-registry ["gc-environments-time"])
   :gc-report-statuses (timer storage-metrics-registry ["gc-report-statuses"])
   :gc-fact-paths  (timer storage-metrics-registry ["gc-fact-paths"])

   :updated-catalog    (counter storage-metrics-registry ["new-catalogs"])
   :duplicate-catalog  (counter storage-metrics-registry ["duplicate-catalogs"])
   :duplicate-pct      (gauge-fn storage-metrics-registry ["duplicate-pct"]
                                 (fn []
                                   (let [dupes (value (:duplicate-catalog performance-metrics))
                                         new   (value (:updated-catalog performance-metrics))]
                                     (float (kitchensink/quotient dupes (+ dupes new))))))
   :catalog-volatility (histogram storage-metrics-registry ["catalog-volitilty"])

   :replace-facts     (timer storage-metrics-registry ["replace-facts-time"])

   :store-report      (timer storage-metrics-registry ["store-report-time"])})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Certname querying/deleting

(defn certname-exists?
  "Returns a boolean indicating whether or not the given certname exists in the db"
  [certname]
  {:pre [certname]}
  (not (empty? (jdbc/query ["SELECT 1 FROM certnames WHERE certname=? LIMIT 1"
                            certname]))))

(defn delete-certname!
  "Delete the given host from the db"
  [certname]
  {:pre [certname]}
  (jdbc/delete! :certnames ["certname=?" certname]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Node activation/deactivation

(defn node-deactivated-time
  "Returns the time the node specified by `certname` was deactivated, or nil if
  the node is currently active."
  [certname]
  {:pre [(string? certname)]}
  (jdbc/query-with-resultset
   ["SELECT deactivated FROM certnames WHERE certname=?" certname]
   (comp :deactivated first sql/result-set-seq)))

(defn node-expired-time
  "Returns the time the node specified by `certname` expired, or nil if
  the node is currently active."
  [certname]
  {:pre [(string? certname)]}
  (jdbc/query-with-resultset
   ["SELECT expired FROM certnames WHERE certname=?" certname]
   (comp :expired first sql/result-set-seq)))

(defn purge-deactivated-and-expired-nodes!
  "Delete nodes from the database which were deactivated before `time`."
  [time]
  {:pre [(kitchensink/datetime? time)]}
  (let [ts (to-timestamp time)]
    (jdbc/delete! :certnames ["deactivated < ? OR expired < ?" ts ts])))

(defn activate-node!
  "Reactivate the given host. Adds the host to the database if it was not
  already present."
  [certname]
  {:pre [(string? certname)]}
  (when-not (certname-exists? certname)
    (add-certname! certname))
  (jdbc/update! :certnames
                {:deactivated nil, :expired nil}
                ["certname=?" certname]))

(pls/defn-validated create-row :- s/Int
  "Creates a row using `row-map` for `table`, returning the PK that was created upon insert"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (:id (first (jdbc/insert! table row-map))))

(pls/defn-validated query-id :- (s/maybe s/Int)
  "Returns the id (primary key) from `table` that contain `row-map` values"
  [table :- s/Keyword
   row-map :- {s/Keyword s/Any}]
  (let [cols (keys row-map)
        q (format "select id from %s where %s"
                  (name table)
                  (str/join " " (map #(str (name %) "=?") cols)))]
    (jdbc/query-with-resultset (apply vector q (map row-map cols))
                               (comp :id first sql/result-set-seq))))

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
  (query-id :environments {:environment env-name}))

(pls/defn-validated certname-id :- (s/maybe s/Int)
  [certname :- s/Str]
  (query-id :certnames {:certname certname}))

(pls/defn-validated ensure-environment :- (s/maybe s/Int)
  "Check if the given `env-name` exists, creates it if it does not. Always returns
   the id of the `env-name` (whether created or existing)"
  [env-name :- (s/maybe s/Str)]
  (when env-name
    (ensure-row :environments {:environment env-name})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Producers querying/updating

(pls/defn-validated producer-id :- (s/maybe s/Int)
  "Returns the id (primary key) from the producers table for the given `prod-name`"
  [prod-name :- s/Str]
  (query-id :producers {:name prod-name}))

(pls/defn-validated ensure-producer :- (s/maybe s/Int)
  "Check if the given `prod-name` exists, creates it if it does not. Always returns
   the id of the `prod-name` (whether created or existing)"
  [prod-name :- (s/maybe s/Str)]
  (when prod-name
    (ensure-row :producers {:name prod-name})))

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

(defn latest-catalog-metadata
  "Returns the id, hash, certname_id and producer_timestamp of certname's
  catalog."
  [certname]
  {:pre [certname]}
  (jdbc/query-with-resultset
   [(format "select catalogs.id as catalog_id, certnames.id as certname_id,
             %s as catalog_hash, catalogs.producer_timestamp
             from certnames
             left join catalogs on catalogs.certname=certnames.certname
             where certnames.certname=?"
            (sutils/sql-hash-as-str "catalogs.hash"))
    certname]
   (comp first sql/result-set-seq)))

(defn munge-edges-for-storage [edges]
  (->> edges
       (map (fn [{:keys [source target relationship]}]
              {:source_type (:type source)
               :source_title (:title source)
               :target_type (:type target)
               :target_title (:title target)
               :relationship relationship}))
       sutils/munge-jsonb-for-storage))

(defn munge-resources-for-storage [resources]
  (->> resources
       (map (partial merge {:file nil :line nil}))
       sutils/munge-jsonb-for-storage))

(s/defn catalog-row-map
  "Creates a row map for the catalogs table, optionally adding envrionment when it was found"
  [hash
   {:keys [edges
           resources
           version
           code_id
           transaction_uuid
           catalog_uuid
           environment
           producer_timestamp
           producer]} :- catalog-schema
   received-timestamp :- pls/Timestamp]
  {:hash (sutils/munge-hash-for-storage hash)
   :catalog_version  version
   :transaction_uuid (sutils/munge-uuid-for-storage transaction_uuid)
   :catalog_uuid (sutils/munge-uuid-for-storage catalog_uuid)
   :timestamp (to-timestamp received-timestamp)
   :code_id code_id
   :environment_id (ensure-environment environment)
   :producer_timestamp (to-timestamp producer_timestamp)
   :producer_id (ensure-producer producer)
   :api_version 1})

(s/defn update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [id :- Long
   hash :- String
   catalog :- catalog-schema
   received-timestamp :- pls/Timestamp]
  (jdbc/update! :catalogs
                (catalog-row-map hash catalog received-timestamp)
                ["id=?" id]))

(s/defn add-catalog-metadata!
  "Given some catalog metadata, persist it in the db. Returns a map of the
  inserted data including any autogenerated columns."
  [hash :- String
   {:keys [certname] :as catalog} :- catalog-schema
   received-timestamp :- pls/Timestamp]
  {:post [(map? %)]}
  (first (jdbc/insert! :catalogs
                       (assoc (catalog-row-map hash catalog received-timestamp)
                              :certname certname))))

(s/defn resources-exist? :- #{String}
  "Given a collection of resource-hashes, return the subset that
   already exist in the database."
  [resource-hashes :- #{String}]
  (if (seq resource-hashes)
    (let [resource-array (->> (vec resource-hashes)
                              (map sutils/munge-hash-for-storage)
                              (sutils/array-to-param "bytea" PGobject))
          query (format "SELECT DISTINCT %s as resource FROM resource_params_cache WHERE resource=ANY(?)"
                        (sutils/sql-hash-as-str "resource"))
          sql-params [query resource-array]]
      (jdbc/query-with-resultset sql-params
                                 #(set (map :resource (sql/result-set-seq %)))))
    #{}))

;;The schema definition of this function should be
;;resource-ref->resource-schema, but there are a lot of tests that
;;have incorrect data. When examples.clj and tests get fixed, this
;;should be changed to the correct schema
(s/defn catalog-resources
  "Returns the resource hashes keyed by resource reference"
  [certname-id :- Long]
  (jdbc/query-with-resultset
   [(format "SELECT type, title, tags, exported, file, line, %s
               AS resource
               FROM catalog_resources
               WHERE certname_id = ?"
            (sutils/sql-hash-as-str "resource")) certname-id]
   (fn [rs]
     (let [rss (sql/result-set-seq rs)]
       (zipmap (map #(select-keys % [:type :title]) rss)
               (for [rowmap rss]
                 (kitchensink/mapvals #(jdbc/convert-any-sql-array % set)
                                      rowmap)))))))

(s/defn new-params-only
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

(s/defn insert-records*
  "Nil/empty safe insert-records, see java.jdbc's insert-records for more "
  [table :- s/Keyword
   record-coll :- [{s/Keyword s/Any}]]
  (jdbc/insert-multi! table record-coll))

(s/defn add-params!
  "Persists the new parameters found in `refs-to-resources` and populates the
   resource_params_cache."
  [refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- resource-ref->hash]
  (let [new-params (new-params-only (resources-exist? (kitchensink/valset refs-to-hashes))
                                    refs-to-resources
                                    refs-to-hashes)]

    (update! (:catalog-volatility performance-metrics) (* 2 (count new-params)))

    (insert-records*
     :resource_params_cache
     (map (fn [[resource-hash params]]
            {:resource (sutils/munge-hash-for-storage resource-hash)
             :parameters (some-> params sutils/munge-jsonb-for-storage)})
          new-params))

    (insert-records*
     :resource_params
     (for [[resource-hash params] new-params
           [k v] params]
       {:resource (sutils/munge-hash-for-storage resource-hash)
        :name (name k)
        :value (sutils/db-serialize v)}))))

(def resource-ref?
  "Returns true of the map is a resource reference"
  (every-pred :type :title))

(defn convert-tags-array
  "Converts the given tags (if present) to the format the database expects"
  [resource]
  (if (contains? resource :tags)
    (update-in resource [:tags] sutils/to-jdbc-varchar-array)
    resource))

(s/defn insert-catalog-resources!
  "Returns a function that accepts a seq of ref keys to insert"
  [certname-id :- Long
   refs-to-hashes :- {resource-ref-schema String}
   refs-to-resources :- resource-ref->resource-schema]
  (fn [refs-to-insert]
    {:pre [(every? resource-ref? refs-to-insert)]}

    (update! (:catalog-volatility performance-metrics) (count refs-to-insert))

    (insert-records*
     :catalog_resources
     (map (fn [resource-ref]
            (let [{:keys [type title exported parameters tags file line] :as resource} (get refs-to-resources resource-ref)]
              (convert-tags-array
               {:certname_id certname-id
                :resource (sutils/munge-hash-for-storage (get refs-to-hashes resource-ref))
                :type type
                :title title
                :tags tags
                :exported exported
                :file file
                :line line})))
          refs-to-insert))))

(s/defn delete-catalog-resources!
  "Returns a function accepts old catalog resources that should be deleted."
  [certname-id :- Long]
  (fn [refs-to-delete]
    {:pre [(every? resource-ref? refs-to-delete)]}

    (update! (:catalog-volatility performance-metrics) (count refs-to-delete))

    (doseq [{:keys [type title]} refs-to-delete]
      (jdbc/delete! :catalog_resources
                    ["certname_id = ? and type = ? and title = ?"
                     certname-id type title]))))

(s/defn basic-diff
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

(s/defn diff-resources-metadata
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

(s/defn update-catalog-resources!
  "Returns a function accepting keys that were the same from the old resources and the new resources."
  [certname-id :- Long
   refs-to-hashes :- {resource-ref-schema String}
   refs-to-resources
   old-resources]

  (fn [maybe-updated-refs]
    {:pre [(every? resource-ref? maybe-updated-refs)]}
    (let [new-resources-with-hash (merge-resource-hash refs-to-hashes (select-keys refs-to-resources maybe-updated-refs))
          updated-resources (->> (diff-resources-metadata old-resources new-resources-with-hash)
                                 (kitchensink/mapvals #(utils/update-when % [:resource] sutils/munge-hash-for-storage)))]

      (update! (:catalog-volatility performance-metrics) (count updated-resources))

      (doseq [[{:keys [type title]} updated-cols] updated-resources]
        (jdbc/update! :catalog_resources
                      (convert-tags-array updated-cols)
                      ["certname_id = ? and type = ? and title = ?"
                       certname-id type title])))))

(defn strip-params
  "Remove params from the resource as it is stored (and hashed) separately
  from the resource metadata"
  [resource]
  (dissoc resource :parameters))

(s/defn add-resources!
  "Persist the given resource and associate it with the given catalog."
  [certname-id :- Long
   refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (let [old-resources (catalog-resources certname-id)
        diffable-resources (kitchensink/mapvals strip-params refs-to-resources)]
    (jdbc/with-db-transaction []
     (add-params! refs-to-resources refs-to-hashes)
     (utils/diff-fn old-resources
                    diffable-resources
                    (delete-catalog-resources! certname-id)
                    (insert-catalog-resources! certname-id refs-to-hashes diffable-resources)
                    (update-catalog-resources! certname-id refs-to-hashes diffable-resources old-resources)))))

(s/defn catalog-edges-map
  "Return all edges for a given catalog id as a map"
  [certname :- String]
  ;; Transform the result-set into a map with [source,target,type] as the key
  ;; and nil as always the value. This just feeds into clojure.data/diff
  ;; better this way.
  (jdbc/query-with-resultset
   [(format "SELECT %s AS source, %s AS target, type FROM edges
               WHERE certname=?"
            (sutils/sql-hash-as-str "source")
            (sutils/sql-hash-as-str "target"))
    certname]
   #(zipmap (map vals (sql/result-set-seq %))
            (repeat nil))))

(s/defn delete-edges!
  "Delete edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, e.g.:

    [[<source> <target> <type>] ...]"
  [certname :- String
   edges :- edge-db-schema]

  (update! (:catalog-volatility performance-metrics) (count edges))
  (doseq [[source target type] edges]
    ;; This is relatively inefficient. If we have id's for edges, we could do
    ;; this in 1 statement.
    (jdbc/delete! :edges
                  [(str "certname=?"
                        " and source=?::bytea"
                        " and target=?::bytea"
                        " and type=?")
                   certname
                   (sutils/bytea-escape source)
                   (sutils/bytea-escape target)
                   type])))

(s/defn insert-edges!
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
                 {:certname certname
                  :source (sutils/munge-hash-for-storage source)
                  :target (sutils/munge-hash-for-storage target)
                  :type type})]

      (update! (:catalog-volatility performance-metrics) (count rows))
      (jdbc/insert-multi! :edges rows))))

(s/defn replace-edges!
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

(s/defn update-existing-catalog
  "When a new incoming catalog has the same hash as an existing catalog, update
   performance-metrics and the transaction id for the new catalog"
  [catalog-id :- Long
   hash :- String
   catalog :- catalog-schema
   received-timestamp :- pls/Timestamp]
  (inc! (:duplicate-catalog performance-metrics))
  (time! (:catalog-hash-match performance-metrics)
         (update-catalog-metadata! catalog-id hash catalog received-timestamp)))

(s/defn update-catalog-associations!
  "Adds/updates/deletes the edges and resources for the given certname"
  [certname-id :- Long
   {:keys [resources edges certname]} :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (time! (:add-resources performance-metrics)
         (add-resources! certname-id resources refs-to-hashes))
  (time! (:add-edges performance-metrics)
         (replace-edges! certname edges refs-to-hashes)))

(s/defn replace-existing-catalog
  "New catalogs for a given certname needs to have their metadata, resources and
  edges updated."
  [certname-id :- Long
   catalog-id :- Long
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   received-timestamp :- pls/Timestamp]

  (inc! (:updated-catalog performance-metrics))

  (time! (:catalog-hash-miss performance-metrics)
         (update-catalog-metadata! catalog-id hash catalog received-timestamp)
         (update-catalog-associations! certname-id catalog refs-to-hashes)))

(s/defn add-new-catalog
  "Creates new catalog metadata and adds the proper associations for the edges and resources"
  [certname-id :- Long
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   received-timestamp :- pls/Timestamp]
  (inc! (:updated-catalog performance-metrics))
  (time! (:add-new-catalog performance-metrics)
         (let [catalog-id (:id (add-catalog-metadata! hash catalog received-timestamp))]
           (update-catalog-associations! certname-id catalog refs-to-hashes))))

(s/defn replace-catalog!
  "Persist the supplied catalog in the database, returning its
   similarity hash."
  ([catalog :- catalog-schema]
   (replace-catalog! catalog (now)))
  ([{:keys [producer_timestamp resources certname] :as catalog} :- catalog-schema
    received-timestamp :- pls/Timestamp]
   (time! (:replace-catalog performance-metrics)
          (jdbc/with-db-transaction []
            (let [hash (time! (:catalog-hash performance-metrics)
                              (shash/catalog-similarity-hash catalog))
                  {catalog-id :catalog_id
                   stored-hash :catalog_hash
                   certname-id :certname_id
                   latest-producer-timestamp :producer_timestamp} (latest-catalog-metadata certname)]
              (cond
                (some-> latest-producer-timestamp
                        (sql-ts-after? (to-timestamp producer_timestamp)))
                (log/warn (trs "Not replacing catalog for certname {0} because local data is newer." certname))

                (= stored-hash hash)
                (update-existing-catalog catalog-id hash catalog received-timestamp)

                :else
                (let [refs-to-hashes (time! (:resource-hashes performance-metrics)
                                            (kitchensink/mapvals shash/resource-identity-hash resources))]
                  (if (nil? catalog-id)
                    (add-new-catalog certname-id hash catalog refs-to-hashes received-timestamp)
                    (replace-existing-catalog certname-id catalog-id hash catalog refs-to-hashes received-timestamp))))
              hash)))))

(defn catalog-hash-for-certname
  "Returns the hash for the `certname` catalog"
  [certname]
  (jdbc/query-with-resultset
   ["SELECT %s as catalog FROM catalogs WHERE certname=?"
    (sutils/sql-hash-as-str "hash") certname]
   (comp :catalog first sql/result-set-seq)))

;; ## Database compaction

(defn delete-unassociated-params!
  "Remove any resources that aren't associated with a catalog"
  []
  (time! (:gc-params performance-metrics)
         (jdbc/delete!
          :resource_params_cache
          ["NOT EXISTS (SELECT * FROM catalog_resources cr
                          WHERE cr.resource=resource_params_cache.resource)"])))


(defn delete-unassociated-environments!
  "Remove any environments that aren't associated with a catalog, report or factset"
  []
  (time!
   (:gc-environments performance-metrics)
   (jdbc/delete!
    :environments
    ["ID NOT IN
        (SELECT environment_id FROM catalogs WHERE environment_id IS NOT NULL
           UNION SELECT environment_id FROM reports
                   WHERE environment_id IS NOT NULL
           UNION SELECT environment_id FROM factsets
                   WHERE environment_id IS NOT NULL)"])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Facts

(defn-validated certname-to-factset-id :- s/Int
  "Given a certname, returns the factset id."
  [certname :- String]
  (jdbc/query-with-resultset
   ["SELECT id from factsets WHERE certname = ?" certname]
   (comp :id first sql/result-set-seq)))

(defn-validated delete-pending-path-id-orphans!
  "Delete paths in dropped-pids that are no longer mentioned
   in other factsets."
  [factset-id dropped-pids]
  (when-let [dropped-pids (seq dropped-pids)]
    (let [dropped-chunks (partition-all gc-chunksize dropped-pids)
          candidate-chunks (map (partial sutils/array-to-param "bigint" Long)
                                dropped-chunks)]
      (dorun
       (map (fn [candidate-paths]
              (jdbc/do-prepared
               "DELETE FROM fact_paths fp
                        WHERE fp.id = ANY (?)
                        AND NOT EXISTS (SELECT 1 FROM facts f
                                        WHERE f.fact_path_id = ANY(?)
                                        AND f.fact_path_id = fp.id
                                        AND f.factset_id <> ?)"
               (concat [candidate-paths]
                       [candidate-paths]
                       [factset-id])))
            candidate-chunks)))))

(defn-validated delete-orphaned-paths! :- s/Int
  "Deletes up to n paths that are no longer mentioned by any factsets,
  and returns the number actually deleted.  These orphans can be
  created by races between parallel updates since (for performance) we
  don't serialize those transactions.  Via repeatable read, an update
  transaction may decide not to delete paths that are only referred to
  by other facts that are being changed in parallel transactions to
  also not refer to the paths."
  [n :- (s/constrained s/Int (complement neg?))]
  (if (zero? n)
    0
    (first
     (jdbc/with-db-transaction []
       (jdbc/do-prepared
        "DELETE FROM fact_paths
           WHERE id IN (SELECT fp.id FROM fact_paths fp
                          WHERE NOT EXISTS (SELECT 1
                                              FROM facts f
                                              WHERE fp.id = f.fact_path_id)
                          LIMIT ?)"
        [n])))))

;; NOTE: now only used in tests.
(defn-validated delete-certname-facts!
  "Delete all the facts for certname."
  [certname :- String]
  (jdbc/with-db-transaction []
    (let [factset-id (certname-to-factset-id certname)
          dead-pids
          (jdbc/call-with-query-rows
           ["select fact_path_id from facts where factset_id = ?"
            factset-id]
           {:as-arrays? true}
           #(mapv first (rest %)))]
      (jdbc/do-commands
       (format "delete from facts where factset_id = %s" factset-id))
      (delete-pending-path-id-orphans! factset-id dead-pids)
      (jdbc/delete! :factsets ["id=?" factset-id]))))

(defn realize-paths!
  "Ensures that all paths exist in the database and returns a map of
  paths to ids."
  [pathstrs]
  (when-let [pathstrs (seq pathstrs)]
    (let [array-to-param sutils/array-to-param
          existing-path-ids (jdbc/call-with-query-rows
                             [(str "select path, id from fact_paths"
                                   "  where path in (select * from unnest(?))")
                              (array-to-param "text" String pathstrs)]
                             {:as-arrays? true}
                             (fn [[col-names & rows]]
                               (into {} rows)))
          missing-db-paths (set/difference (set pathstrs)
                                           (set (keys existing-path-ids)))
          new-paths (map facts/string-to-factpath missing-db-paths)]

      (jdbc/call-with-query-rows
       [(str "insert into fact_paths (path, name, depth)"
             "  (select * from unnest(?, ?, ?)) returning path, id")
        (array-to-param "text" String (map facts/factpath-to-string new-paths))
        (array-to-param "text" String (map first new-paths))
        (array-to-param "integer" Integer (map #(-> (count %) dec int)
                                               new-paths))]
       {:as-arrays? true}
       (fn [[col-names & rows]]
         (into existing-path-ids rows))))))

(def ^:const db-fv-string 0)
(def ^:const db-fv-int 1)
(def ^:const db-fv-float 2)
(def ^:const db-fv-bool 3)
(def ^:const db-fv-nil 4)
(def ^:const db-fv-struct 5)

(defn fact-value-type-id [x]
  (cond
    (keyword? x) db-fv-string
    (string? x) db-fv-string
    (integer? x) db-fv-int
    (float? x) db-fv-float
    (kitchensink/boolean? x) db-fv-bool
    (nil? x) db-fv-nil
    (coll? x) db-fv-struct
    :else (throw (Exception. (trs "Fact value {0} has unexpected type"
                                  (pr-str x))))))

(def value-row-cols
  ;; Must match order in value->value-row immediately below
  [:value_type_id
   :large_value_hash
   :value
   :value_string
   :value_integer
   :value_float
   :value_boolean])

(defn storage-hash [x]
  ;; If the return type changes here, update the PGobject check in update-facts.
  (-> x shash/generic-identity-hash sutils/munge-hash-for-storage))

(defn value->value-row
  ([value] (value->value-row value nil))
  ([value hash]
   (let [jsonb (when-not (nil? value)
                 (sutils/munge-jsonb-for-storage value))
         large? (and jsonb (>= (count (.getValue ^PGobject jsonb))
                               facts/large-value-threshold))
         hash (when large?
                (or hash (storage-hash value)))]
     (value->value-row value hash jsonb)))
  ([value hash jsonb]
   (let [id (fact-value-type-id value)]
     (condp = id
       db-fv-string [id hash jsonb value nil nil nil]
       db-fv-int    [id hash jsonb nil value nil nil]
       db-fv-float  [id hash jsonb nil nil value nil]
       db-fv-bool   [id hash jsonb nil nil nil value]
       db-fv-struct [id hash jsonb nil nil nil nil]
       db-fv-nil    [id hash jsonb nil nil nil nil]
       (throw (Exception. (trs "Unexpected type {0} for {1}"
                               (pr-str id) (pr-str value))))))))

(defn insert-facts! [factset-id pids values]
  (when (seq values)
    (let [cols (concat [:factset_id :fact_path_id] value-row-cols)]
      (jdbc/insert-multi! :facts
                          cols
                          (map (fn [pid value]
                                 (apply vector
                                        factset-id pid
                                        (value->value-row value)))
                               pids
                               values)))))

(defn find-certname-id-and-hash [certname]
  (first (jdbc/query-to-vec [(format "SELECT id, %s as package_hash FROM certnames WHERE certname=?"
                                     (sutils/sql-hash-as-str "package_hash"))
                             certname])))

(defn create-package-map [certname-id]
  (jdbc/call-with-query-rows ["SELECT id, name, version, provider FROM package_inventory WHERE certname_id=?"
                              certname-id]
                             (fn [rows]
                               (->> rows
                                    (map (fn [{id :id package_name :name version :version provider :provider}]
                                           [[package_name version provider] id]))
                                    (into {})))))

(s/defn update-packages
  "Compares `inventory` to the stored package inventory for
  `certname`. Differences will result in updates to the database"
  [certname
   inventory :- [facts/package-tuple]]
  (let [{certname-id :id package_hash :package_hash} (find-certname-id-and-hash certname)
        new-package-hash (shash/package-similarity-hash inventory)]

    (when-not (= new-package-hash package_hash)
      (let [old-packages (create-package-map certname-id)
            [to-add to-remove _] (clojure.data/diff (set inventory) (set (keys old-packages)))
            to-remove-ids (map old-packages to-remove)]
        (jdbc/update! :certnames
                      {:package_hash (sutils/munge-hash-for-storage new-package-hash)}
                      ["id=?" certname-id])
        (when (seq to-add)
          (jdbc/insert-multi! :package_inventory
                              ["certname_id" "name" "version" "provider"]
                              (map #(cons certname-id %) to-add)))
        (when (seq to-remove)
          (jdbc/delete! :package_inventory
                        ["id = ANY(?)"
                         (sutils/array-to-param "bigint" Long to-remove-ids)]))))))


(defn insert-packages [certname inventory]
  (let [certname-id (:id (find-certname-id-and-hash certname))
        new-package-hash (shash/package-similarity-hash inventory)]
    (jdbc/update! :certnames
                  {:package_hash (sutils/munge-hash-for-storage new-package-hash)}
                  ["id=?" certname-id])
    (jdbc/insert-multi! :package_inventory
                        ["certname_id" "name" "version" "provider"]
                        (map (fn [[package_name version provider]]
                               [certname-id
                                package_name
                                version
                                provider])
                             inventory))))

(pls/defn-validated add-facts!
  "Given a certname and a map of fact names to values, store records for those
  facts associated with the certname."
  ([fact-data] (add-facts! fact-data true))
  ([{:keys [certname values environment timestamp producer_timestamp producer package_inventory]
     :as fact-data} :- facts-schema
    include-hash? :- s/Bool]
   (jdbc/with-db-transaction []
     (jdbc/insert!
      :factsets
      (merge
       {:certname certname
        :timestamp (to-timestamp timestamp)
        :environment_id (ensure-environment environment)
        :producer_timestamp (to-timestamp producer_timestamp)
        :producer_id (ensure-producer producer)}
       (when include-hash?
         {:hash (sutils/munge-hash-for-storage
                 (shash/fact-identity-hash fact-data))})))
    (let [paths-and-values (facts/facts->paths-and-values values)
          pathstrs (map (comp facts/factpath-to-string first) paths-and-values)
          paths->ids (realize-paths! pathstrs)
          path-values (map second paths-and-values)]

      (when (seq package_inventory)
        (insert-packages certname package_inventory))

      (insert-facts! (certname-to-factset-id certname)
                     (map paths->ids pathstrs)
                     path-values)))))

(defn- update-existing-values [factset-id change-info]
  (when (seq change-info)
    (sql/db-do-prepared
     jdbc/*db* true
     (cons
      (str "update facts set "
           (str/join ", " (map (fn [col] (str (name col) " = ?"))
                              value-row-cols))
           "  where factset_id = ? and fact_path_id = ?")
      (map (fn [[_ path path-id new-val new-hash]]
             (conj (if new-hash
                     (value->value-row new-val new-hash)
                     (value->value-row new-val))
                   factset-id
                   path-id))
           change-info))
     {:multi? true})))

(defn compare-fv-to-row
  "Returns a map with a logically false :changed? value if new-val
  does not differ from the row data, and a logically true value
  otherwise.  If a hash of new-val was computed during the
  process and changed? is true, it will be returned in :hash."
  [new-val type-id hash v vstr vint vfloat vbool]
  (if (not= type-id (fact-value-type-id new-val))
    {:changed? true}
    (cond
      ;; faster direct compare
      (or (= type-id db-fv-int)
          (= type-id db-fv-float)
          (= type-id db-fv-bool))
      {:changed? (not= new-val (or vint vfloat vbool))}

      ;; nil is a singleton
      (= type-id db-fv-nil) {:changed? false}

      ;; things that may have a hash
      (or (= type-id db-fv-string)
          (= type-id db-fv-struct))
      (if hash
        (let [new-hash (storage-hash new-val)]
          (if (= hash new-hash)
            {:changed? false}
            {:changed? true :hash new-hash}))
        (cond
          (= type-id db-fv-string)
          {:changed? (not= new-val vstr)}
          (= type-id db-fv-struct)
          {:changed? (not= new-val (sutils/parse-db-json v))}
          :else
          (throw (Exception. (trs "Unexpected type {0} for {1}"
                                  (pr-str type-id) (pr-str new-val))))))
      :else
      (throw (Exception. (trs "Unexpected type {0} for {1}"
                              (pr-str type-id) (pr-str new-val)))))))

(s/defn update-facts!
  "Given a certname, querys the DB for existing facts for that
   certname and will update, delete or insert the facts as necessary
   to match the facts argument. (cf. add-facts!)"
  [{:keys [certname values environment timestamp producer_timestamp producer package_inventory]
    :as fact-data} :- facts-schema]
  (jdbc/with-db-transaction []
    (let [factset-id (certname-to-factset-id certname)
          paths-and-values (facts/facts->paths-and-values values)
          pathstrs (map (comp facts/factpath-to-string first) paths-and-values)
          pathstr->value (into {} (map (fn [pathstr [_ v]] [pathstr v])
                                       pathstrs
                                       paths-and-values))
          existing-status ;; :changed :unchanged :gone
          (jdbc/call-with-query-rows
           [(str "select"
                 "    path, id, value_type_id, large_value_hash,"
                 "    case when large_value_hash is null "
                 "              and value_type_id = " db-fv-struct
                 "           then value"
                 "    end as value,"
                 "    case when large_value_hash is null then value_string"
                 "    end as value_string,"
                 "    value_integer, value_float, value_boolean"
                 "  from facts as f"
                 "  inner join fact_paths fp on f.fact_path_id = fp.id"
                 "  where f.factset_id = ?")
            factset-id]
           {:as-arrays? true}
           (fn [[col-names & rows]]
             (doall
              (map (fn [[path id type-id hash v vstr vint vfloat vbool]]
                     ;; Classify row as :changed :unchanged: :gone
                     (let [new-val (pathstr->value path ::not-found)]
                       (if (= new-val ::not-found)
                         [:gone path id]
                         (let [{:keys [changed? hash]}
                               (compare-fv-to-row new-val
                                                  type-id hash
                                                  v vstr vint vfloat vbool)]
                           (if-not changed?
                             [:unchanged path id]
                             (if hash
                               [:changed path id new-val hash]
                               [:changed path id new-val]))))))
                   rows))))
          rm-pids (seq (for [[st _ pid] existing-status
                             :when (= :gone st)]
                         pid))
          new-pathstrs (let [have? (set (for [[st path] existing-status
                                              :when #(#{:changed :unchanged} st)]
                                          path))]
                         (remove have? pathstrs))
          new-values (map pathstr->value new-pathstrs)
          paths->ids (realize-paths! new-pathstrs)]

      ;; Paths are unique per factset so we can delete solely based on pid.
      (when rm-pids
        (jdbc/do-prepared
         (format "delete from facts where factset_id = ? and fact_path_id %s"
                 (jdbc/in-clause rm-pids))
         (cons factset-id rm-pids)))

      (insert-facts! (certname-to-factset-id certname)
                     (map paths->ids new-pathstrs)
                     new-values)

      (update-existing-values factset-id
                              (filter #(= :changed (first %))
                                      existing-status))

      (delete-pending-path-id-orphans! factset-id rm-pids)

      (when (seq package_inventory)
        (update-packages certname package_inventory))

      (jdbc/update! :factsets
                    {:timestamp (to-timestamp timestamp)
                     :environment_id (ensure-environment environment)
                     :producer_timestamp (to-timestamp producer_timestamp)
                     :hash (-> fact-data
                               shash/fact-identity-hash
                               sutils/munge-hash-for-storage)
                     :producer_id (ensure-producer producer)}
                    ["id=?" factset-id]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reports

(defn update-latest-report!
  "Given a node name, updates the `certnames` table to ensure that it indicates the
   most recent report for the node."
  [node]
  {:pre [(string? node)]}
  (let [latest-report (:id (first (query-to-vec
                                    ["SELECT id FROM reports
                                      WHERE certname = ?
                                      ORDER BY producer_timestamp DESC
                                      LIMIT 1" node])))]
    (jdbc/update! :certnames
                  {:latest_report_id latest-report}
                  ["certname = ?" node])))

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

(def store-resources-column? (atom false))

(defn maybe-resources
  [row-map]
  (if @store-resources-column?
    row-map
    (dissoc row-map :resources)))

(def store-corrective-change? (atom false))

(defn maybe-corrective-change
  [row-map]
  (if @store-corrective-change?
    row-map
    (dissoc row-map :corrective_change)))

(defn maybe-environment
  "This fn is most to help in testing, instead of persisting a value of
  nil, just omit it from the row map. For tests that are running older versions
  of migrations, this function prevents a failure"
  [row-map]
  (if (nil? (:environment_id row-map))
    (dissoc row-map :environment_id)
    row-map))

(defn normalize-resource-event
  "Prep `event` for comparison/computation of a hash"
  [event]
  (-> event
      (update :timestamp to-timestamp)
      (update :old_value sutils/db-serialize)
      (update :new_value sutils/db-serialize)
      (assoc :containing_class (find-containing-class (:containment_path event)))))

(defn normalize-report
  "Prep the report for comparison/computation of a hash"
  [{:keys [resources] :as report}]
  (-> report
      (update :start_time to-timestamp)
      (update :end_time to-timestamp)
      (update :producer_timestamp to-timestamp)
      (assoc :resource_events (->> resources
                                   reports/resources->resource-events
                                   (map normalize-resource-event)))))

(s/defn add-report!*
  "Helper function for adding a report.  Accepts an extra parameter, `update-latest-report?`, which
  is used to determine whether or not the `update-latest-report!` function will be called as part of
  the transaction.  This should always be set to `true`, except during some very specific testing
  scenarios."
  [orig-report :- reports/report-wireformat-schema
   received-timestamp :- pls/Timestamp
   update-latest-report? :- s/Bool]
  (time! (:store-report performance-metrics)
         (let [{:keys [puppet_version certname report_format configuration_version producer
                       producer_timestamp start_time end_time transaction_uuid environment
                       status noop metrics logs resources resource_events catalog_uuid
                       code_id cached_catalog_status noop_pending corrective_change]
                :as report} (normalize-report orig-report)
                report-hash (shash/report-identity-hash report)]
           (jdbc/with-db-transaction []
             (let [shash (sutils/munge-hash-for-storage report-hash)]
               (when-not (-> "select 1 from reports where hash = ? limit 1"
                             (query-to-vec shash)
                             seq)
                 (let [certname-id (certname-id certname)
                       row-map {:hash shash
                                :transaction_uuid (sutils/munge-uuid-for-storage transaction_uuid)
                                :catalog_uuid (sutils/munge-uuid-for-storage catalog_uuid)
                                :code_id code_id
                                :cached_catalog_status cached_catalog_status
                                :metrics (sutils/munge-jsonb-for-storage metrics)
                                :logs (sutils/munge-jsonb-for-storage logs)
                                :resources (sutils/munge-jsonb-for-storage resources)
                                :corrective_change corrective_change
                                :noop noop
                                :noop_pending noop_pending
                                :puppet_version puppet_version
                                :certname certname
                                :report_format report_format
                                :configuration_version configuration_version
                                :producer_id (ensure-producer producer)
                                :producer_timestamp producer_timestamp
                                :start_time start_time
                                :end_time end_time
                                :receive_time (to-timestamp received-timestamp)
                                :environment_id (ensure-environment environment)
                                :status_id (ensure-status status)}
                       [{report-id :id}] (->> row-map
                                              maybe-environment
                                              maybe-resources
                                              maybe-corrective-change
                                              (jdbc/insert! :reports))
                       adjust-event-metadata #(-> %
                                                  (assoc :report_id report-id
                                                         :certname_id certname-id)
                                                  maybe-corrective-change)]
                   (when-not (empty? resource_events)
                     (->> resource_events
                          (sp/transform [sp/ALL :containment_path] #(some-> % sutils/to-jdbc-varchar-array))
                          (map adjust-event-metadata)
                          (jdbc/insert-multi! :resource_events)
                          dorun))
                   (when update-latest-report?
                     (update-latest-report! certname)))))))))

(defn delete-reports-older-than!
  "Delete all reports in the database which have an `producer-timestamp` that is prior to
   the specified date/time."
  [time]
  {:pre [(kitchensink/datetime? time)]}
  (jdbc/delete! :reports ["producer_timestamp < ?" (to-timestamp time)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database support/deprecation

(defn db-unsupported-msg
  "Returns a string with an unsupported message if the DB is not supported,
  nil otherwise."
  []
  (when (sutils/db-version-older-than? [9 4])
    (str "PostgreSQL DB versions older than 9.4 are no longer supported."
         "  Please upgrade Postgres and restart PuppetDB.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(pls/defn-validated add-certname!
  "Add the given host to the db"
  [certname :- String]
  (jdbc/insert! :certnames {:certname certname}))

(defn timestamp-of-newest-record [entity certname]
  (let [query {:select [:producer_timestamp]
               :from [entity]
               :where [:= :certname certname]
               :order-by [[:producer_timestamp :desc]]
               :limit 1}]
    (:producer_timestamp (first (jdbc/query (hcore/format query))))))

(pls/defn-validated have-record-produced-after?
  [entity :- s/Keyword
   certname :- String
   time :- pls/Timestamp]
  (let [time (to-timestamp time)]
    (boolean
     (some-> entity
             (timestamp-of-newest-record certname)
             (sql-ts-after? time)))))

(pls/defn-validated have-newer-record-for-certname?
  [certname :- String
   timestamp :- pls/Timestamp]
  "Returns a truthy value indicating whether a record exists that has
  a producer_timestamp newer than the given timestamp."
  (some (fn [entity]
          (have-record-produced-after? entity certname timestamp))
        [:catalogs :factsets :reports]))

(pls/defn-validated maybe-activate-node!
  "Reactivate the given host, only if it was deactivated or expired before
  `time`.  Returns true if the node is activated, or if it was already active.

  Adds the host to the database if it was not already present."
  [certname :- String
   time :- pls/Timestamp]
  (when-not (certname-exists? certname)
    (add-certname! certname))
  (let [timestamp (to-timestamp time)
        replaced  (jdbc/update! :certnames
                                {:deactivated nil, :expired nil}
                                ["certname=? AND (deactivated<? OR expired<?)"
                                 certname timestamp timestamp])]
    (pos? (first replaced))))

(pls/defn-validated deactivate-node!
  "Deactivate the given host, recording the current time. If the node is
  currently inactive, no change is made."
  ([certname :- String]
   (deactivate-node! certname (now)))
  ([certname :- String timestamp :- pls/Timestamp]
   (if (have-newer-record-for-certname? certname timestamp)
     (log/warn (trs "Not deactivating node {0} because local data is newer than {1}."
                    certname timestamp))
     (let [sql-timestamp (to-timestamp timestamp)]
       (jdbc/do-prepared "UPDATE certnames SET deactivated = ?
                            WHERE certname=?
                              AND (deactivated IS NULL OR deactivated < ?)"
                         [sql-timestamp certname sql-timestamp])))))

(pls/defn-validated expire-stale-nodes [horizon :- Period]
  "Expires nodes with no activity within the provided horizon (prior
  to now) and returns a collection of the affected certnames."
  (let [stale-start-ts (to-timestamp (ago horizon))
        expired-ts (to-timestamp (now))]
    (map :certname
         (jdbc/query-to-vec
          (str
           "update certnames set expired = ?"
           "  where id in"
           "    (select c.id from certnames c
                     left outer join catalogs cats on cats.certname = c.certname
                     left outer join factsets fs on c.certname = fs.certname
                     left outer join reports r on c.latest_report_id = r.id
                   where c.deactivated is null
                     and c.expired is null
                     and (cats.producer_timestamp is null
                          or cats.producer_timestamp < ?)
                     and (fs.producer_timestamp is null
                          or fs.producer_timestamp < ?)
                     and (r.producer_timestamp is null
                          or r.producer_timestamp < ?))"
           "  returning certname")
          expired-ts stale-start-ts stale-start-ts stale-start-ts))))

(pls/defn-validated replace-facts!
  "Updates the facts of an existing node, if the facts are newer than the current set of facts.
   Adds all new facts if no existing facts are found. Invoking this function under the umbrella of
   a repeatable read or serializable transaction enforces only one update to the facts of a certname
   can happen at a time.  The first to start the transaction wins.  Subsequent transactions will fail
   as the factsets will have changed while the transaction was in-flight."
  [{:keys [certname producer_timestamp] :as fact-data} :- facts-schema]
  (time! (:replace-facts performance-metrics)
         (if-let [local-factset-producer-ts (timestamp-of-newest-record :factsets certname)]
           (if-not (sql-ts-after? local-factset-producer-ts
                                  (to-timestamp producer_timestamp))
             (update-facts! fact-data)
             (log/warn (trs "Not updating facts for certname {0} because local data is newer." certname)))
           (add-facts! fact-data))))

(s/defn add-report!
  "Add a report and all of the associated events to the database."
  [report :- reports/report-wireformat-schema
   received-timestamp :- pls/Timestamp]
  (add-report!* report received-timestamp true))

(defn validate-database-version
  "Check the currently configured database and if it isn't supported,
  notify the user and call fail-fn.  Then (if fail-fn returns) notify
  the user if the database is deprecated."
  [fail-fn]
  (when-let [msg (db-unsupported-msg)]
    (let [msg (utils/attention-msg msg)]
      (utils/println-err msg)
      (log/error msg)
      (fail-fn))))

(def ^:dynamic *orphaned-path-gc-limit* 200)

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  [db]
  (time!
   (:gc performance-metrics)
   (jdbc/with-transacted-connection db
     (delete-unassociated-params!)
     (delete-unassociated-environments!))
   ;; These require serializable because they make the decision to
   ;; delete based on row counts in another table.
   (jdbc/with-transacted-connection' db :serializable
     (delete-orphaned-paths! *orphaned-path-gc-limit*))))
