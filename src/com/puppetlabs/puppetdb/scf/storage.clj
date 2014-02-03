;; ## Catalog persistence
;;
;; Catalogs are persisted in a relational database. Roughly speaking,
;; the schema looks like this:
;;
;; * resource_parameters are associated 0 to N catalog_resources (they are
;; deduped across catalogs). It's possible for a resource_param to exist in the
;; database, yet not be associated with a catalog. This is done as a
;; performance optimization.
;;
;; * edges are associated with a single catalog
;;
;; * catalogs are associated with a single certname
;;
;; * facts are associated with a single certname
;;
;; The standard set of operations on information in the database will
;; likely result in dangling resources and catalogs; to clean these
;; up, it's important to run `garbage-collect!`.

(ns com.puppetlabs.puppetdb.scf.storage
  (:require [com.puppetlabs.puppetdb.catalogs :as cat]
            [com.puppetlabs.puppetdb.reports :as report]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.jdbc :as jdbc]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.puppetlabs.cheshire :as json]
            [clojure.data :as data]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [com.puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [com.puppetlabs.puppetdb.scf.hash-debug :as hashdbg]
            [schema.core :as s]
            [schema.macros :as sm]
            [com.puppetlabs.puppetdb.schema :as pls]
            [com.puppetlabs.puppetdb.utils :as utils])
  (:use [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [ago secs now before?]]
        [metrics.meters :only (meter mark!)]
        [metrics.counters :only (counter inc! value)]
        [metrics.gauges :only (gauge)]
        [metrics.histograms :only (histogram update!)]
        [metrics.timers :only (timer time!)]
        [com.puppetlabs.jdbc :only [query-to-vec dashes->underscores]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def resource-ref-schema
  {:type String
   :title String})

(def json-primitive-schema (s/either String Number pls/SchemaBoolean))

(def resource-schema
  (merge resource-ref-schema
         {(s/optional-key :exported) pls/SchemaBoolean
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
  {:api-version s/Int
   :version String
   :certname String
   :puppetdb-version s/Int
   :resources resource-ref->resource-schema
   :edges #{edge-schema}
   (s/optional-key :transaction-uuid) (s/maybe String)})

(def fact-map-schema
  {String json-primitive-schema})

(def facts-schema
  {(s/required-key "name") String
   (s/required-key "values") fact-map-schema
   ;;These next two should not be necessary, it's due to a bug in the
   ;;terminus code.  Leaving this in until 2.0.  If the user hasn't
   ;;and the below two lines are in, it will fails (sees keys it
   ;;doesn't recognize).  Remove this at 2.0.
   (s/optional-key "timestamp") s/Any
   (s/optional-key "expiration") s/Any})

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

(def ns-str (str *ns*))

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
                                   LEFT OUTER JOIN certname_facts_metadata fm ON c.name=fm.certname
                                   WHERE c.deactivated IS NULL
                                   AND (clogs.timestamp IS NULL OR clogs.timestamp < ?)
                                   AND (fm.timestamp IS NULL OR fm.timestamp < ?)"
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog updates/changes

(defn catalog-metadata
  "Returns the id and hash of certname's catalog"
  [certname]
  {:pre [certname]}
  (sql/with-query-results result-set
    ["SELECT id, hash FROM catalogs WHERE certname=?" certname]
    (first result-set)))

(sm/defn ^:always-validate update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [id :- Number
   hash :- String
   {:keys [api-version version transaction-uuid]} :- catalog-schema
   timestamp :- pls/Timestamp]
  (sql/update-values :catalogs
                     ["id=?" id]
                     {:hash hash
                      :api_version      api-version
                      :catalog_version  version
                      :transaction_uuid transaction-uuid
                      :timestamp (to-timestamp timestamp)}))

(sm/defn ^:always-validate add-catalog-metadata!
  "Given some catalog metadata, persist it in the db. Returns a map of the
  inserted data including any autogenerated columns."
  [hash :- String
   {:keys [api-version version transaction-uuid certname]} :- catalog-schema
   timestamp :- pls/Timestamp]
  {:post [(map? %)]}
  (first (sql/insert-records :catalogs
                             {:hash hash
                              :api_version api-version
                              :catalog_version version
                              :transaction_uuid transaction-uuid
                              :certname certname
                              :timestamp (to-timestamp timestamp)})))

(sm/defn ^:always-validate resources-exist? :- #{String}
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
(sm/defn ^:always-validate catalog-resources
  "Returns the resource hashes keyed by resource reference"
  [catalog-id :- Number]
  (sql/with-query-results result-set
    ["SELECT type, title, tags, exported, file, line, resource
      FROM catalog_resources
      WHERE catalog_id = ?" catalog-id]
    (zipmap (map #(select-keys % [:type :title]) result-set)
            (jdbc/convert-result-arrays set result-set))))

(sm/defn ^:always-validate new-params-only
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

(sm/defn ^:always-validate insert-records*
  "Nil/empty safe insert-records, see java.jdbc's insert-records for more "
  [table :- s/Keyword
   record-coll :- [{s/Keyword s/Any}]]
  (when (seq record-coll)
    (apply sql/insert-records table record-coll)))

(sm/defn ^:always-validate add-params!
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

(sm/defn ^:always-validate insert-catalog-resources!
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

(sm/defn ^:always-validate delete-catalog-resources!
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

(sm/defn ^:always-validate update-catalog-resources!
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

(sm/defn ^:always-validate add-resources!
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

(sm/defn ^:always-validate catalog-edges-map
  "Return all edges for a given catalog id as a map"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT source, target, type FROM edges WHERE certname=?" certname]
    ;; Transform the result-set into a map with [source,target,type] as the key
    ;; and nil as always the value. This just feeds into clojure.data/diff
    ;; better this way.
    (zipmap (map vals result-set)
            (repeat nil))))

(sm/defn ^:always-validate delete-edges!
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

(sm/defn ^:always-validate insert-edges!
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

(sm/defn ^:always-validate replace-edges!
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

(sm/defn ^:always-validate update-catalog-hash-match
  "When a new incoming catalog has the same hash as an existing catalog, update metrics
   and the transaction id for the new catalog"
  [catalog-id :- Number
   hash :- String
   catalog :- catalog-schema
   timestamp :- pls/Timestamp]
  (inc! (:duplicate-catalog metrics))
  (time! (:catalog-hash-match metrics)
         (update-catalog-metadata! catalog-id hash catalog timestamp)))

(sm/defn ^:always-validate update-catalog-associations!
  "Adds/updates/deletes the edges and resources for the given certname"
  [catalog-id :- Number
   {:keys [resources edges certname]} :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (time! (:add-resources metrics)
         (add-resources! catalog-id resources refs-to-hashes))
  (time! (:add-edges metrics)
         (replace-edges! certname edges refs-to-hashes)))

(sm/defn ^:always-validate update-catalog-hash-miss
  "New catalogs for a given certname needs to have their metadata, resources and edges updated.  This
   function also outputs debugging related information when `catalog-hash-debug-dir` is not nil"
  [catalog-id :- Number
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   catalog-hash-debug-dir :- (s/maybe String)
   timestamp :- pls/Timestamp]

  (inc! (:updated-catalog metrics))

  (when catalog-hash-debug-dir
    (hashdbg/debug-catalog catalog-hash-debug-dir hash catalog))

  (time! (:catalog-hash-miss metrics)
         (update-catalog-metadata! catalog-id hash catalog timestamp)
         (update-catalog-associations! catalog-id catalog refs-to-hashes)))

(sm/defn ^:always-validate add-new-catalog
  "Creates new catalog metadata and adds the proper associations for the edges and resources"
  [hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   timestamp :- pls/Timestamp]
  (inc! (:updated-catalog metrics))
  (time! (:add-new-catalog metrics)
         (let [catalog-id (:id (add-catalog-metadata! hash catalog timestamp))]
           (update-catalog-associations! catalog-id catalog refs-to-hashes))))

(sm/defn ^:always-validate add-catalog!
  "Persist the supplied catalog in the database, returning its
   similarity hash. `catalog-hash-debug-dir` is an optional path that
   indicates where catalog debugging information should be stored."
  ([catalog :- catalog-schema]
     (add-catalog! catalog nil (now)))
  ([{:keys [api-version resources edges certname] :as catalog} :- catalog-schema
    catalog-hash-debug-dir :- (s/maybe String)
    timestamp :- pls/Timestamp]

     (let [refs-to-hashes (time! (:resource-hashes metrics)
                                 (zipmap (keys resources)
                                         (map shash/resource-identity-hash (vals resources))))
           hash           (time! (:catalog-hash metrics)
                                 (shash/catalog-similarity-hash catalog))
           {id :id
            stored-hash :hash} (catalog-metadata certname)]

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

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  []
  (time! (:gc metrics)
         (sql/transaction
          (delete-unassociated-params!))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Facts

(sm/defn ^:always-validate insert-facts!
  "Given a certname and map of fact/value keypairs, insert them into the facts table"
  [certname :- String
   facts :- fact-map-schema]
  (let [default-row {:certname certname}
        rows        (for [[fact value] facts]
                      (assoc default-row :name fact :value value))]
    (apply sql/insert-records :certname_facts rows)))

(sm/defn ^:always-validate add-facts!
  "Given a certname and a map of fact names to values, store records for those
  facts associated with the certname."
  [certname :- String
   facts :- fact-map-schema
   timestamp :- pls/Timestamp]
  {:pre [(kitchensink/datetime? timestamp)]}
  (sql/insert-record :certname_facts_metadata
                     {:certname certname :timestamp (to-timestamp timestamp)})
  (insert-facts! certname facts))

(sm/defn ^:always-validate delete-facts!
  "Delete all the facts (1 arg) or just the fact-names (2 args) for the given certname."
  ([certname :- String]
     (sql/delete-rows :certname_facts_metadata ["certname=?" certname]))
  ([certname :- String
    fact-names :- #{String}]
     (when (seq fact-names)
       (sql/delete-rows :certname_facts
                        (into [(str "certname=? and name " (jdbc/in-clause fact-names)) certname]  fact-names)))))

(sm/defn ^:always-validate cert-fact-map
  "Return all facts and their values for a given certname as a map"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT name, value FROM certname_facts WHERE certname=?" certname]
    (zipmap (map :name result-set)
            (map :value result-set))))

(sm/defn ^:always-validate update-existing-facts!
  "Issues a SQL Update for `shared-keys` whose value in old-facts
   is different from new-facts"
  [certname :- String
   old-facts :- (s/either fact-map-schema {})
   new-facts :- (s/either fact-map-schema {})
   shared-keys :- #{String}]
  (doseq [k shared-keys]
    (let [old-val (get old-facts k)
          new-val (get new-facts k)]
      (when-not (= old-val new-val)
        (sql/update-values :certname_facts ["certname=? and name=?" certname k] {:value new-val})))))

(sm/defn ^:always-validate update-facts!
  "Given a certname, querys the DB for existing facts for that
   certname and will update, delete or insert the facts as necessary
   to match the facts argument."
  [certname :- String
   facts :- fact-map-schema
   timestamp :- pls/Timestamp]
  (let [old-facts (cert-fact-map certname)]
    (sql/update-values :certname_facts_metadata ["certname=?" certname]
                       {:timestamp (to-timestamp timestamp)})

    (utils/diff-fn old-facts
                   facts
                   #(delete-facts! certname %)
                   #(insert-facts! certname (select-keys facts %))
                   #(update-existing-facts! certname old-facts facts %))))

(sm/defn ^:always-validate certname-facts-metadata!
  "Return the certname_facts_metadata timestamp for the given certname, nil if not found"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT timestamp FROM certname_facts_metadata WHERE certname=? ORDER BY timestamp DESC" certname]
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

(defn add-report!*
  "Helper function for adding a report.  Accepts an extra parameter, `update-latest-report?`, which
  is used to determine whether or not the `update-latest-report!` function will be called as part of
  the transaction.  This should always be set to `true`, except during some very specific testing
  scenarios."
  [{:keys [puppet-version certname report-format configuration-version
           start-time end-time resource-events transaction-uuid]
    :as report}
   timestamp update-latest-report?]
  {:pre [(map? report)
         (kitchensink/datetime? timestamp)
         (kitchensink/boolean? update-latest-report?)]}
  (let [report-hash         (shash/report-identity-hash report)
        containment-path-fn (fn [cp] (if-not (nil? cp) (sutils/to-jdbc-varchar-array cp)))
        resource-event-rows (map #(-> %
                                      (update-in [:timestamp] to-timestamp)
                                      (update-in [:old-value] sutils/db-serialize)
                                      (update-in [:new-value] sutils/db-serialize)
                                      (update-in [:containment-path] containment-path-fn)
                                      (assoc :containing-class (find-containing-class (% :containment-path)))
                                      (assoc :report report-hash) ((partial kitchensink/mapkeys dashes->underscores)))
                                 resource-events)]
    (time! (:store-report metrics)
           (sql/transaction
            (sql/insert-record :reports
                               { :hash                   report-hash
                                :puppet_version         puppet-version
                                :certname               certname
                                :report_format          report-format
                                :configuration_version  configuration-version
                                :start_time             (to-timestamp start-time)
                                :end_time               (to-timestamp end-time)
                                :receive_time           (to-timestamp timestamp)
                                :transaction_uuid       transaction-uuid})
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

(defn db-deprecated?
  "Returns a string with an deprecation message if the DB is deprecated,
   nil otherwise."
  []
  (when (sutils/pg-8-4?)
    "PostgreSQL DB 8.4 is deprecated and won't be supported in the future."))

(defn db-unsupported?
  "Returns a string with an unsupported message if the DB is not supported,
   nil otherwise."
  []
  (when (sutils/pg-older-than-8-4?)
    "PostgreSQL DB versions 8.3 and older are no longer supported. Please upgrade Postgres and restart PuppetDB."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(sm/defn ^:always-validate add-certname!
  "Add the given host to the db"
  [certname :- String]
  (sql/insert-record :certnames {:name certname}))

(sm/defn ^:always-validate maybe-activate-node!
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

(sm/defn ^:always-validate deactivate-node!
  "Deactivate the given host, recording the current time. If the node is
  currently inactive, no change is made."
  [certname :- String]
  (sql/do-prepared "UPDATE certnames SET deactivated = ?
                    WHERE name=? AND deactivated IS NULL"
                   [(to-timestamp (now)) certname]))

(sm/defn ^:always-validate catalog-newer-than?
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

(sm/defn ^:always-validate replace-catalog!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one. `catalog-hash-debug-dir`
  is an optional path that indicates where catalog debugging information
  should be stored."
  ([catalog :- catalog-schema
    timestamp :- pls/Timestamp]
     (replace-catalog! catalog timestamp nil))
  ([{:keys [certname] :as catalog} :- catalog-schema
    timestamp :- pls/Timestamp
    catalog-hash-debug-dir :- (s/maybe String)]
     (time! (:replace-catalog metrics)
            (sql/transaction
             (add-catalog! catalog catalog-hash-debug-dir timestamp)))))

(sm/defn ^:always-validate replace-facts!
  "Updates the facts of an existing node, if the facts are newer than the current set of facts.
   Adds all new facts if no existing facts are found. Invoking this function under the umbrella of
   a repeatable read or serializable transaction enforces only one update to the facts of a certname
   can happen at a time.  The first to start the transaction wins.  Subsequent transactions will fail
   as the certname_facts_metadata will have changed while the transaction was in-flight."
  [{:strs [name values]} :- facts-schema
   timestamp :- pls/Timestamp]
  (time! (:replace-facts metrics)
         (if-let [facts-meta-ts (certname-facts-metadata! name)]
           (when (.before facts-meta-ts (to-timestamp timestamp))
             (update-facts! name values timestamp))
           (add-facts! name values timestamp))))

(sm/defn ^:always-validate add-report!
  "Add a report and all of the associated events to the database."
  [report
   timestamp :- pls/Timestamp]
  (add-report!* report timestamp true))

(defn warn-on-db-deprecation
  "Log a warning message if the database is deprecated"
  []
  (when-let [deprecated-message (db-deprecated?)]
    (log/warn deprecated-message)))

(defn fail-on-unsupported
  "Log an error message to the log and console if the currently
   configured database is unsupported, then call fail-fn  (probably to
   exit)."
  [fail-fn]
  (let [msg (db-unsupported?)]
    (when-let [attn-msg (and msg (utils/attention-warning-msg msg))]
      (utils/println-err attn-msg)
      (log/error attn-msg)
      (fail-fn))))

(defn validate-database-version
  "Checks to ensure that the database is supported, fails if supported, logs
   if deprecated"
  [action-for-unsupported-fn]
  (fail-on-unsupported action-for-unsupported-fn)
  (warn-on-db-deprecation))
