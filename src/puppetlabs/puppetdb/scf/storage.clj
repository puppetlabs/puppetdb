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
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [com.rpl.specter :as sp]
            [clojure.java.jdbc :as sql]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.data :as data]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.scf.hash-debug :as hashdbg]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.core :refer [now]]
            [metrics.counters :refer [counter inc! value]]
            [metrics.gauges :refer [gauge]]
            [metrics.histograms :refer [histogram update!]]
            [metrics.timers :refer [timer time!]]
            [puppetlabs.puppetdb.jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [honeysql.core :as hcore])
  (:import [org.postgresql.util PGobject]))

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
(def performance-metrics
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
                              (let [dupes (value (:duplicate-catalog performance-metrics))
                                    new   (value (:updated-catalog performance-metrics))]
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
  (not (empty? (jdbc/query ["SELECT 1 FROM certnames WHERE certname=? LIMIT 1"
                            certname]))))

(defn delete-certname!
  "Delete the given host from the db"
  [certname]
  {:pre [certname]}
  (jdbc/delete! :certnames ["certname=?" certname]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Node activation/deactivation

(defn stale-nodes
  "Return a list of nodes that have seen no activity between
  (now-`time` and now)"
  [time]
  {:pre  [(kitchensink/datetime? time)]
   :post [(coll? %)]}
  (let [ts (to-timestamp time)]
    (map :certname (jdbc/query-to-vec "SELECT c.certname FROM certnames c
                                       LEFT OUTER JOIN catalogs clogs ON c.certname=clogs.certname
                                       LEFT OUTER JOIN factsets fs ON c.certname=fs.certname
                                       LEFT OUTER JOIN reports r ON c.certname=r.certname
                                       WHERE c.deactivated IS NULL
                                       AND c.expired IS NULL
                                       AND (clogs.producer_timestamp IS NULL OR clogs.producer_timestamp < ?)
                                       AND (fs.producer_timestamp IS NULL OR fs.producer_timestamp < ?)
                                       AND (r.producer_timestamp IS NULL OR r.producer_timestamp < ?)"
                                      ts ts ts))))

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
  (jdbc/query-with-resultset
   [(format "SELECT id, %s AS hash FROM catalogs WHERE certname=?"
            (sutils/sql-hash-as-str "catalogs.hash"))
    certname]
   (comp first sql/result-set-seq)))

(pls/defn-validated catalog-row-map
  "Creates a row map for the catalogs table, optionally adding envrionment when it was found"
  [hash
   {:keys [version code_id transaction_uuid environment producer_timestamp]} :- catalog-schema
   received-timestamp :- pls/Timestamp]
  {:hash (sutils/munge-hash-for-storage hash)
   :catalog_version  version
   :transaction_uuid (sutils/munge-uuid-for-storage transaction_uuid)
   :timestamp (to-timestamp received-timestamp)
   :code_id code_id
   :environment_id (ensure-environment environment)
   :producer_timestamp (to-timestamp producer_timestamp)
   :api_version 1})

(pls/defn-validated update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [id :- Number
   hash :- String
   catalog :- catalog-schema
   received-timestamp :- pls/Timestamp]
  (jdbc/update! :catalogs
                (catalog-row-map hash catalog received-timestamp)
                ["id=?" id]))

(pls/defn-validated add-catalog-metadata!
  "Given some catalog metadata, persist it in the db. Returns a map of the
  inserted data including any autogenerated columns."
  [hash :- String
   {:keys [certname] :as catalog} :- catalog-schema
   received-timestamp :- pls/Timestamp]
  {:post [(map? %)]}
  (first (jdbc/insert! :catalogs
                       (assoc (catalog-row-map hash catalog received-timestamp)
                              :certname certname))))

(pls/defn-validated resources-exist? :- #{String}
  "Given a collection of resource-hashes, return the subset that
  already exist in the database."
  [resource-hashes :- #{String}]
  (if (seq resource-hashes)
    (let [query (apply vector
                       (format "SELECT DISTINCT %s AS resource FROM resource_params_cache WHERE resource %s"
                               (sutils/sql-hash-as-str "resource")
                               (jdbc/in-clause resource-hashes))
                       (map sutils/munge-hash-for-storage resource-hashes))]
      (jdbc/query-with-resultset query
                                 #(set (map :resource (sql/result-set-seq %)))))
    #{}))

;;The schema definition of this function should be
;;resource-ref->resource-schema, but there are a lot of tests that
;;have incorrect data. When examples.clj and tests get fixed, this
;;should be changed to the correct schema
(pls/defn-validated catalog-resources
  "Returns the resource hashes keyed by resource reference"
  [catalog-id :- Number]
  (jdbc/query-with-resultset
   [(format "SELECT type, title, tags, exported, file, line, %s
               AS resource
               FROM catalog_resources
               WHERE catalog_id = ?"
            (sutils/sql-hash-as-str "resource")) catalog-id]
   (fn [rs]
     (let [rss (sql/result-set-seq rs)]
       (zipmap (map #(select-keys % [:type :title]) rss)
               (jdbc/convert-result-arrays set rss))))))

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
    (apply jdbc/insert! table record-coll)))

(pls/defn-validated add-params!
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
            {:resource (sutils/munge-hash-for-storage resource-hash) :parameters (when params (sutils/db-serialize params))})
          new-params))

    (insert-records*
     :resource_params
     (for [[resource-hash params] new-params
           [k v] params]
       {:resource (sutils/munge-hash-for-storage resource-hash) :name (name k) :value (sutils/db-serialize v)}))))

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

    (update! (:catalog-volatility performance-metrics) (count refs-to-insert))

    (insert-records*
     :catalog_resources
     (map (fn [resource-ref]
            (let [{:keys [type title exported parameters tags file line] :as resource} (get refs-to-resources resource-ref)]
              (convert-tags-array
               {:catalog_id catalog-id
                :resource (sutils/munge-hash-for-storage (get refs-to-hashes resource-ref))
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

    (update! (:catalog-volatility performance-metrics) (count refs-to-delete))

    (doseq [{:keys [type title]} refs-to-delete]
      (jdbc/delete! :catalog_resources
                    ["catalog_id = ? and type = ? and title = ?"
                     catalog-id type title]))))

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

(pls/defn-validated update-catalog-resources!
  "Returns a function accepting keys that were the same from the old resources and the new resources."
  [catalog-id :- Number
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
                      ["catalog_id = ? and type = ? and title = ?"
                       catalog-id type title])))))

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
    (jdbc/with-db-transaction []
     (add-params! refs-to-resources refs-to-hashes)
     (utils/diff-fn old-resources
                    diffable-resources
                    (delete-catalog-resources! catalog-id)
                    (insert-catalog-resources! catalog-id refs-to-hashes diffable-resources)
                    (update-catalog-resources! catalog-id refs-to-hashes diffable-resources old-resources)))))

(pls/defn-validated catalog-edges-map
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

(pls/defn-validated delete-edges!
  "Delete edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, eg:

    [[<source> <target> <type>] ...]"
  [certname :- String
   edges :- edge-db-schema]

  (update! (:catalog-volatility performance-metrics) (count edges))

  (doseq [[source target type] edges]
    ;; This is relatively inefficient. If we have id's for edges, we could do
    ;; this in 1 statement.
    (jdbc/delete! :edges
                  [(format "certname=? and %s=? and %s=? and type=?"
                           (sutils/sql-hash-as-str "source")
                           (sutils/sql-hash-as-str "target"))
                   certname source target type])))

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
                 {:certname certname
                  :source (sutils/munge-hash-for-storage source)
                  :target (sutils/munge-hash-for-storage target)
                  :type type})]

      (update! (:catalog-volatility performance-metrics) (count rows))
      (apply jdbc/insert! :edges rows))))

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
  "When a new incoming catalog has the same hash as an existing catalog, update performance-metrics
   and the transaction id for the new catalog"
  [catalog-id :- Number
   hash :- String
   catalog :- catalog-schema
   received-timestamp :- pls/Timestamp]
  (inc! (:duplicate-catalog performance-metrics))
  (time! (:catalog-hash-match performance-metrics)
         (update-catalog-metadata! catalog-id hash catalog received-timestamp)))

(pls/defn-validated update-catalog-associations!
  "Adds/updates/deletes the edges and resources for the given certname"
  [catalog-id :- Number
   {:keys [resources edges certname]} :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (time! (:add-resources performance-metrics)
         (add-resources! catalog-id resources refs-to-hashes))
  (time! (:add-edges performance-metrics)
         (replace-edges! certname edges refs-to-hashes)))

(pls/defn-validated update-catalog-hash-miss
  "New catalogs for a given certname needs to have their metadata, resources and edges updated.  This
   function also outputs debugging related information when `catalog-hash-debug-dir` is not nil"
  [catalog-id :- Number
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   catalog-hash-debug-dir :- (s/maybe s/Str)
   received-timestamp :- pls/Timestamp]

  (inc! (:updated-catalog performance-metrics))

  (when catalog-hash-debug-dir
    (hashdbg/debug-catalog catalog-hash-debug-dir hash catalog))

  (time! (:catalog-hash-miss performance-metrics)
         (update-catalog-metadata! catalog-id hash catalog received-timestamp)
         (update-catalog-associations! catalog-id catalog refs-to-hashes)))

(pls/defn-validated add-new-catalog
  "Creates new catalog metadata and adds the proper associations for the edges and resources"
  [hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   received-timestamp :- pls/Timestamp]
  (inc! (:updated-catalog performance-metrics))
  (time! (:add-new-catalog performance-metrics)
         (let [catalog-id (:id (add-catalog-metadata! hash catalog received-timestamp))]
           (update-catalog-associations! catalog-id catalog refs-to-hashes))))

(pls/defn-validated add-catalog!
  "Persist the supplied catalog in the database, returning its
   similarity hash. `catalog-hash-debug-dir` is an optional path that
   indicates where catalog debugging information should be stored."
  ([catalog :- catalog-schema]
   (add-catalog! catalog nil (now)))
  ([{:keys [api_version resources edges certname] :as catalog} :- catalog-schema
    catalog-hash-debug-dir :- (s/maybe s/Str)
    received-timestamp :- pls/Timestamp]

   (let [refs-to-hashes (time! (:resource-hashes performance-metrics)
                               (reduce-kv (fn [acc k v]
                                            (assoc acc k (shash/resource-identity-hash v)))
                                          {} resources))
         hash (time! (:catalog-hash performance-metrics)
                     (shash/catalog-similarity-hash catalog))
         {id :id
          stored-hash :hash} (catalog-metadata certname)]

     (jdbc/with-db-transaction []
      (cond
       (nil? id)
       (add-new-catalog hash catalog refs-to-hashes received-timestamp)

       (= stored-hash hash)
       (update-catalog-hash-match id hash catalog received-timestamp)

       :else
       (update-catalog-hash-miss id hash catalog refs-to-hashes catalog-hash-debug-dir received-timestamp)))

     hash)))

(defn delete-catalog!
  "Remove the catalog identified by the following hash"
  [catalog-hash]
  (jdbc/delete! :catalogs [(str (sutils/sql-hash-as-str "hash") "=?")
                           catalog-hash]))

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

(defn delete-unassociated-statuses!
  "Remove any statuses that aren't associated with a report"
  []
  (time! (:gc-report-statuses performance-metrics)
         (jdbc/delete! :report_statuses
                       ["ID NOT IN (SELECT status_id FROM reports)"])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Facts

(defn-validated select-pid-vid-pairs-for-factset
  :- [(s/pair s/Int "path-id" s/Int "value-id")]
  "Return a collection of pairs of [path-id value-id] for the indicated factset."
  [factset-id :- s/Int]
  (for [{:keys [fact_path_id fact_value_id]}
        (query-to-vec "SELECT fact_path_id, fact_value_id FROM facts
                         WHERE factset_id = ?" factset-id)]
    [fact_path_id fact_value_id]))

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
    (if (sutils/postgres?)
      (let [candidate-paths (sutils/array-to-param "bigint" Long dropped-pids)]
        (jdbc/do-prepared
         "DELETE FROM fact_paths fp
            WHERE fp.id = ANY (?)
              AND NOT EXISTS (SELECT 1 FROM facts f
                                WHERE f.fact_path_id = ANY (?)
                                  AND f.fact_path_id = fp.id
                                  AND f.factset_id <> ?)"
         (concat [candidate-paths]
                 [candidate-paths]
                 [factset-id])))
      (let [in-pids (jdbc/in-clause dropped-pids)]
        (jdbc/do-prepared
         (format
          "DELETE FROM fact_paths fp
             WHERE fp.id %s
               AND NOT EXISTS (SELECT 1 FROM facts f
                                 WHERE f.fact_path_id %s
                                   AND f.fact_path_id = fp.id
                                   AND f.factset_id <> ?)"
          in-pids in-pids)
         (concat dropped-pids dropped-pids [factset-id]))))))

(defn-validated delete-pending-value-id-orphans!
  "Delete values in removed-pid-vid-pairs that are no longer mentioned
  in facts."
  [factset-id removed-pid-vid-pairs]
  (when-let [removed-pid-vid-pairs (seq removed-pid-vid-pairs)]
    (let [vids (map second removed-pid-vid-pairs)
          rm-facts (map cons (repeat factset-id) removed-pid-vid-pairs)
          in-vids (jdbc/in-clause vids)
          in-rm-facts (jdbc/in-clause-multi rm-facts 3)]
      (jdbc/do-prepared
       (format
        "DELETE FROM fact_values fv
           WHERE fv.id %s
             AND NOT EXISTS (SELECT 1 FROM facts f
                               WHERE f.fact_value_id %s
                                 AND f.fact_value_id = fv.id
                                 AND (f.factset_id,
                                      f.fact_path_id,
                                      f.fact_value_id) NOT %s)"
        in-vids in-vids in-rm-facts)
       (flatten [vids vids rm-facts])))))

(defn-validated delete-orphaned-paths! :- s/Int
  "Deletes up to n paths that are no longer mentioned by any factsets,
  and returns the number actually deleted.  These orphans can be
  created by races between parallel updates since (for performance) we
  don't serialize those transactions.  Via repeatable read, an update
  transaction may decide not to delete paths that are only referred to
  by other facts that are being changed in parallel transactions to
  also not refer to the paths."
  [n :- (s/both s/Int (s/pred (complement neg?) 'nonnegative?))]
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

(defn-validated delete-orphaned-values! :- s/Int
  "Deletes up to n values that are no longer mentioned by any
  factsets, and returns the number actually deleted.  These orphans
  can be created by races between parallel updates since (for
  performance) we don't serialize those transactions.  Via repeatable
  read, an update transaction may decide not to delete values that are
  only referred to by other facts that are being changed in parallel
  transactions to also not refer to the values."
  [n :- (s/both s/Int (s/pred (complement neg?) 'nonnegative?))]
  (if (zero? n)
    0
    (first
     (jdbc/with-db-transaction []
       (jdbc/do-prepared
        "DELETE FROM fact_values
           WHERE id in (SELECT fv.id
                          FROM fact_values fv
                          WHERE NOT EXISTS (SELECT 1
                                              FROM facts f
                                              WHERE fv.id = f.fact_value_id)
                          LIMIT ?)"
        [n])))))

;; NOTE: now only used in tests.
(defn-validated delete-certname-facts!
  "Delete all the facts for certname."
  [certname :- String]
  (jdbc/with-db-transaction []
    (let [factset-id (certname-to-factset-id certname)
          dead-pairs (select-pid-vid-pairs-for-factset factset-id)]
      (jdbc/do-commands (format "DELETE FROM facts WHERE factset_id = %s"
                                factset-id))
      (delete-pending-path-id-orphans! factset-id (set (map first dead-pairs)))
      (delete-pending-value-id-orphans! factset-id dead-pairs)
      (jdbc/delete! :factsets ["id=?" factset-id]))))

(defn-validated insert-facts-pv-pairs!
  [factset-id :- s/Int
   pairs :- (s/either [(s/pair s/Int "path-id" s/Int "value-id")]
                      #{(s/pair s/Int "path-id" s/Int "value-id")})]
  (when-let [rows (seq (for [[pid vid] pairs]
                         {:factset_id factset-id
                          :fact_path_id pid
                          :fact_value_id vid}))]
    (apply jdbc/insert! :facts rows)))

(defn existing-row-ids
  "Returns a map from value to id for each value that's already in the
   named database column.
   `column-transform` is used to modify the sql for the values"
  [table column values column-transform java-type sql-type]
  (into {}
        (for [{:keys [value id]}
              (if (sutils/postgres?)
                (query-to-vec
                  (format
                    "WITH values AS (SELECT unnest(?) as vals)
                     SELECT %s AS value, id FROM %s
                     INNER JOIN values ON values.vals = %s.%s"
                    (column-transform column) (name table) (name table) column)
                  (sutils/array-to-param sql-type java-type values))
                (apply query-to-vec
                       (format "SELECT %s AS value, id FROM %s WHERE %s %s"
                               (column-transform column) (name table) column (jdbc/in-clause values))
                       values))]
          [value id])))

(defn realize-records!
  "Inserts the records (maps) into the named table and returns them
  with their new :id values."
  [table records]
  (when (seq records)
    (map #(assoc %2 :id %1)
         (map :id (apply jdbc/insert! table records))
         records)))

(defn realize-paths!
  "Ensures that all paths exist in the database and returns a map of
  paths to ids."
  [pathstrs]
  (if-let [pathstrs (seq pathstrs)]
    (let [existing-path-ids (existing-row-ids :fact_paths "path" pathstrs identity
                                              String "text")

          missing-db-paths (set/difference (set pathstrs)
                                           (set (keys existing-path-ids)))]
      (merge existing-path-ids
             (into {}
                   (map #(vector (:path %) (:id %))
                        (realize-records!
                         :fact_paths
                         (map (comp facts/path->pathmap facts/string-to-factpath)
                              missing-db-paths))))))
    {}))

(defn realize-values!
  "Ensures that all valuemaps exist in the database and returns a
  map of value hashes to ids."
  [valuemaps]
  (if-let [valuemaps (seq valuemaps)]
    (let [vhashes (map :value_hash valuemaps)
          existing-vhash-ids (existing-row-ids :fact_values "value_hash"
                                               (map sutils/munge-hash-for-storage vhashes)
                                               sutils/sql-hash-as-str
                                               PGobject "bytea")
          missing-vhashes (set/difference (set vhashes)
                                          (set (keys existing-vhash-ids)))]
      (merge existing-vhash-ids
             (into {}
                   (map #(vector (sutils/parse-db-hash (:value_hash %)) (:id %))
                        (realize-records!
                         :fact_values
                         (->> valuemaps
                              (filter (comp missing-vhashes :value_hash))
                              set
                              (map #(update-in % [:value_hash] sutils/munge-hash-for-storage))))))))
    {}))

(pls/defn-validated add-facts!
  "Given a certname and a map of fact names to values, store records for those
  facts associated with the certname."
  ([fact-data] (add-facts! fact-data true))
  ([{:keys [certname values environment timestamp producer_timestamp]
     :as fact-data} :- facts-schema
    include-hash? :- s/Bool]
   (jdbc/with-db-transaction []
     (jdbc/insert!
      :factsets
      (merge
       {:certname certname
        :timestamp (to-timestamp timestamp)
        :environment_id (ensure-environment environment)
        :producer_timestamp (to-timestamp producer_timestamp)}
       (when include-hash?
         {:hash (sutils/munge-hash-for-storage
                 (shash/generic-identity-hash
                  (dissoc fact-data :timestamp :producer_timestamp)))})))
    ;; Ensure that all the required paths and values exist, and then
    ;; insert the new facts.
    (let [paths-and-valuemaps (facts/facts->paths-and-valuemaps values)
          pathstrs (map (comp facts/factpath-to-string first) paths-and-valuemaps)
          valuemaps (map second paths-and-valuemaps)
          vhashes (map :value_hash valuemaps)
          paths-to-ids (realize-paths! pathstrs)
          vhashes-to-ids (realize-values! valuemaps)]
      (insert-facts-pv-pairs! (certname-to-factset-id certname)
                              (map #(vector (get paths-to-ids %1)
                                            (get vhashes-to-ids %2))
                                   (map facts/unescape-string pathstrs)
                                   vhashes))))))

(defn-validated update-facts!
  "Given a certname, querys the DB for existing facts for that
   certname and will update, delete or insert the facts as necessary
   to match the facts argument. (cf. add-facts!)"
  [{:keys [certname values environment timestamp producer_timestamp] :as fact-data}
   :- facts-schema]

  (jdbc/with-db-transaction []
    (let [factset-id (certname-to-factset-id certname)
          initial-factset-paths-vhashes
          (query-to-vec
           (format "SELECT fp.path, %s AS value_hash FROM facts f
                      INNER JOIN fact_paths fp ON f.fact_path_id = fp.id
                      INNER JOIN fact_values fv ON f.fact_value_id = fv.id
                      WHERE factset_id = ?"
                   (sutils/sql-hash-as-str "fv.value_hash"))
           factset-id)
         ;; Ensure that all the required paths and values exist.
         paths-and-valuemaps (facts/facts->paths-and-valuemaps values)
         pathstrs (map (comp facts/factpath-to-string first) paths-and-valuemaps)
         valuemaps (map second paths-and-valuemaps)
         vhashes (map :value_hash valuemaps)
         paths-to-ids (realize-paths! pathstrs)
         vhashes-to-ids (realize-values! valuemaps)
         ;; Add new facts and remove obsolete facts.
         replacement-pv-pairs (set (map #(vector (paths-to-ids %1)
                                                 (vhashes-to-ids %2))
                                        (map facts/unescape-string pathstrs)
                                        vhashes))
         current-pairs (set (select-pid-vid-pairs-for-factset factset-id))
         [new-pairs rm-pairs] (data/diff replacement-pv-pairs current-pairs)]

     ;; Paths are unique per factset so we can delete solely based on pid.
     (when rm-pairs
       (let [rm-pids (set (map first rm-pairs))]
         (jdbc/do-prepared
          (format "DELETE FROM facts WHERE factset_id = ? AND fact_path_id %s"
                  (jdbc/in-clause rm-pids))
          (cons factset-id rm-pids))))

     (insert-facts-pv-pairs! factset-id new-pairs)

     (when rm-pairs
       (delete-pending-path-id-orphans! factset-id
                                        (set/difference
                                         (set (map first rm-pairs))
                                         (set (map first new-pairs))))
       (delete-pending-value-id-orphans! factset-id rm-pairs))

     (jdbc/update! :factsets
                   {:timestamp (to-timestamp timestamp)
                    :environment_id (ensure-environment environment)
                    :producer_timestamp (to-timestamp producer_timestamp)
                    :hash (-> (dissoc fact-data :timestamp :producer_timestamp)
                              shash/generic-identity-hash
                              sutils/munge-hash-for-storage)}
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
                                      ORDER BY end_time DESC
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

(pls/defn-validated add-report!*
  "Helper function for adding a report.  Accepts an extra parameter, `update-latest-report?`, which
  is used to determine whether or not the `update-latest-report!` function will be called as part of
  the transaction.  This should always be set to `true`, except during some very specific testing
  scenarios."
  [orig-report :- reports/report-wireformat-schema
   received-timestamp :- pls/Timestamp
   update-latest-report? :- s/Bool]
  (time! (:store-report performance-metrics)
         (let [{:keys [puppet_version certname report_format configuration_version
                       producer_timestamp start_time end_time transaction_uuid environment
                       status noop metrics logs resources resource_events]
                :as report} (normalize-report orig-report)
                report-hash (shash/report-identity-hash report)]
           (jdbc/with-db-transaction []
             (let [certname-id (certname-id certname)
                   row-map {:hash (sutils/munge-hash-for-storage report-hash)
                            :transaction_uuid (sutils/munge-uuid-for-storage transaction_uuid)
                            :metrics (sutils/munge-jsonb-for-storage metrics)
                            :logs (sutils/munge-jsonb-for-storage logs)
                            :resources (sutils/munge-jsonb-for-storage resources)
                            :noop noop
                            :puppet_version puppet_version
                            :certname certname
                            :report_format report_format
                            :configuration_version configuration_version
                            :producer_timestamp producer_timestamp
                            :start_time start_time
                            :end_time end_time
                            :receive_time (to-timestamp received-timestamp)
                            :environment_id (ensure-environment environment)
                            :status_id (ensure-status status)}
                   [{report-id :id}] (->> row-map
                                          maybe-environment
                                          maybe-resources
                                          (jdbc/insert! :reports))
                   assoc-ids #(assoc %
                                     :report_id report-id
                                     :certname_id certname-id)]
               (when-not (empty? resource_events)
                 (->> resource_events
                      (sp/transform [sp/ALL :containment_path] #(some-> % sutils/to-jdbc-varchar-array))
                      (map assoc-ids)
                      (apply jdbc/insert! :resource_events)))
               (when update-latest-report?
                 (update-latest-report! certname)))))))

(defn delete-reports-older-than!
  "Delete all reports in the database which have an `producer-timestamp` that is prior to
   the specified date/time."
  [time]
  {:pre [(kitchensink/datetime? time)]}
  (when (not (sutils/postgres?))
    ;; there's an ON DELETE SET NULL foreign key constraint in postgres for
    ;; this, but we can't do that in hsqldb
    (jdbc/update! :certnames
                  {:latest_report_id nil}
                  ["latest_report_id in (select id from reports where producer_timestamp < ?)"
                   (to-timestamp time)]))
  (jdbc/delete! :reports ["producer_timestamp < ?" (to-timestamp time)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database support/deprecation

(defn db-deprecated-msg
  "Returns a string with a deprecation message if the DB is deprecated,
   nil otherwise."
  []
  (when-not (sutils/postgres?)
    (str "HSQLDB support has been deprecated and will be removed in a future version. "
         "Please migrate to PostgreSQL.")))

(defn db-unsupported-msg
  "Returns a string with an unsupported message if the DB is not supported,
  nil otherwise."
  []
  (when (and (sutils/postgres?)
             (sutils/db-version-older-than? [9 4]))
    "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."))

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
             (.after time)))))

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
     (log/warnf "Not deactivating node %s because local data is newer than %s."
                certname timestamp)
     (let [sql-timestamp (to-timestamp timestamp)]
       (jdbc/do-prepared "UPDATE certnames SET deactivated = ?
                            WHERE certname=?
                              AND (deactivated IS NULL OR deactivated < ?)"
                         [sql-timestamp certname sql-timestamp])))))

(pls/defn-validated expire-node!
  "Expire the given host, recording the current time. If the node is
  currently expired, no change is made."
  [certname :- String & [timestamp :- pls/Timestamp]]
  (let [timestamp (to-timestamp (or timestamp (now)))]
    (jdbc/do-prepared "UPDATE certnames SET expired = ?
                         WHERE certname=? AND expired IS NULL"
                      [timestamp certname])))

(pls/defn-validated catalog-newer-than?
  "Returns true if the most current catalog for `certname` is more recent than
  `time`."
  [certname :- String
   producer-timestamp :- pls/Timestamp]
  (have-record-produced-after? :catalogs certname producer-timestamp))

(pls/defn-validated replace-catalog!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one. `catalog-hash-debug-dir`
  is an optional path that indicates where catalog debugging information
  should be stored."
  ([catalog :- catalog-schema
    received-timestamp :- pls/Timestamp]
   (replace-catalog! catalog received-timestamp nil))
  ([{:keys [certname] :as catalog} :- catalog-schema
    received-timestamp :- pls/Timestamp
    catalog-hash-debug-dir :- (s/maybe s/Str)]
   (time! (:replace-catalog performance-metrics)
          (jdbc/with-db-transaction []
            (add-catalog! catalog catalog-hash-debug-dir received-timestamp)))))

(pls/defn-validated replace-facts!
  "Updates the facts of an existing node, if the facts are newer than the current set of facts.
   Adds all new facts if no existing facts are found. Invoking this function under the umbrella of
   a repeatable read or serializable transaction enforces only one update to the facts of a certname
   can happen at a time.  The first to start the transaction wins.  Subsequent transactions will fail
   as the factsets will have changed while the transaction was in-flight."
  [{:keys [certname producer_timestamp] :as fact-data} :- facts-schema]
  (time! (:replace-facts performance-metrics)
         (if-let [local-factset-producer-ts (timestamp-of-newest-record :factsets certname)]
           (if-not (.after local-factset-producer-ts (to-timestamp producer_timestamp))
             (update-facts! fact-data)
             (log/warnf "Not updating facts for certname %s because local data is newer." certname))
           (add-facts! fact-data))))

(pls/defn-validated add-report!
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
      (fail-fn)))
  (when-let [msg (db-deprecated-msg)]
    (log/warn msg)))

(def ^:dynamic *orphaned-path-gc-limit* 200)
(def ^:dynamic *orphaned-value-gc-limit* 200)

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  [db]
  (time!
   (:gc performance-metrics)
   (jdbc/with-transacted-connection db
     (delete-unassociated-params!)
     (delete-unassociated-environments!)
     (delete-unassociated-statuses!))
   ;; These require serializable because they make the decision to
   ;; delete based on row counts in another table.
   (jdbc/with-transacted-connection' db :serializable
     (delete-orphaned-paths! *orphaned-path-gc-limit*))
   (jdbc/with-transacted-connection' db :serializable
     (delete-orphaned-values! *orphaned-value-gc-limit*))))
