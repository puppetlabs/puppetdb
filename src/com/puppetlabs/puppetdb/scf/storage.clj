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
            [com.puppetlabs.puppetdb.scf.hash-debug :as hashdbg])
  (:use [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [ago secs now before?]]
        [metrics.meters :only (meter mark!)]
        [metrics.counters :only (counter inc! value)]
        [metrics.gauges :only (gauge)]
        [metrics.histograms :only (histogram update!)]
        [metrics.timers :only (timer time!)]
        [com.puppetlabs.jdbc :only [query-to-vec dashes->underscores]]))

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

;; ## Entity manipulation

(defn certname-exists?
  "Returns a boolean indicating whether or not the given certname exists in the db"
  [certname]
  {:pre [certname]}
  (sql/with-query-results result-set
    ["SELECT 1 FROM certnames WHERE name=? LIMIT 1" certname]
    (pos? (count result-set))))

(defn add-certname!
  "Add the given host to the db"
  [certname]
  {:pre [certname]}
  (sql/insert-record :certnames {:name certname}))

(defn delete-certname!
  "Delete the given host from the db"
  [certname]
  {:pre [certname]}
  (sql/delete-rows :certnames ["name=?" certname]))

(defn deactivate-node!
  "Deactivate the given host, recording the current time. If the node is
  currently inactive, no change is made."
  [certname]
  {:pre [(string? certname)]}
  (sql/do-prepared "UPDATE certnames SET deactivated = ?
                    WHERE name=? AND deactivated IS NULL"
                   [(to-timestamp (now)) certname]))

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

(defn maybe-activate-node!
  "Reactivate the given host, only if it was deactivated before `time`.
  Returns true if the node is activated, or if it was already active.

  Adds the host to the database if it was not already present."
  [certname time]
  {:pre [(string? certname)]}
  (when-not (certname-exists? certname)
    (add-certname! certname))
  (let [timestamp (to-timestamp time)
        replaced  (sql/update-values :certnames
                                     ["name=? AND (deactivated<? OR deactivated IS NULL)" certname timestamp]
                                     {:deactivated nil})]
    (pos? (first replaced))))

(defn catalog-metadata
  "Returns the id and hash of certname's catalog"
  [certname]
  {:pre [certname]}
  (sql/with-query-results result-set
    ["SELECT id, hash FROM catalogs WHERE certname=?" certname]
    (first result-set)))

;; TODO: Add schema checks here
(defn update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [id hash {:keys [api-version version transaction-uuid]} timestamp]
  {:pre [(number? id)
         (string? hash)
         (number? api-version)
         (string? version)
         ((some-fn nil? string?) transaction-uuid)]}
  (sql/update-values :catalogs
                     ["id=?" id]
                     {:hash hash
                      :api_version      api-version
                      :catalog_version  version
                      :transaction_uuid transaction-uuid
                      :timestamp (to-timestamp timestamp)}))

(defn add-catalog-metadata!
  "Given some catalog metadata, persist it in the db. Returns a map of the
  inserted data including any autogenerated columns."
  [hash {:keys [api-version version transaction-uuid certname]} timestamp]
  {:pre [(string? hash)
         (number? api-version)
         (string? version)
         ((some-fn nil? string?) transaction-uuid)]
   :post [(map? %)]}
  (let [return (first (sql/insert-records :catalogs
                                          {:hash hash
                                           :api_version api-version
                                           :catalog_version version
                                           :transaction_uuid transaction-uuid
                                           :certname certname
                                           :timestamp (to-timestamp timestamp)}))]

    ;; PostgreSQL <= 8.1 does not support RETURNING so we fake it
    (if (and (sutils/postgres?) (not (sutils/pg-newer-than-8-1?)))
      (first (query-to-vec ["SELECT * FROM catalogs WHERE hash = ?" hash]))
      return)))

(defn resources-exist?
  "Given a collection of resource-hashes, return the subset that
  already exist in the database."
  [resource-hashes]
  {:pre  [(coll? resource-hashes)
          (every? string? resource-hashes)]
   :post [(set? %)]}
  (let [qmarks     (str/join "," (repeat (count resource-hashes) "?"))
        query      (format "SELECT DISTINCT resource FROM resource_params_cache WHERE resource IN (%s)" qmarks)
        sql-params (vec (cons query resource-hashes))]
    (sql/with-query-results result-set
      sql-params
      (set (map :resource result-set)))))

(defn- resource->values
  "Given a catalog-hash, a resource, and a truthy value indicating
  whether or not the indicated resource already exists somewhere in
  the database, return a map representing the set of database rows
  pending insertion.

  The result map has the following format:

    {:resource [[<catalog id> <resouce hash> <type> <title> <tags> <exported?> <sourcefile> <sourceline>] ...]
     :parameters [[<resource hash> <name> <value>] ...]
     :parameters_cache [[<resource hash> <parameters>] ...]}

  The result map format may seem arbitrary and confusing, but its best
  to think about it in 2 ways:

  1. Each key corresponds to a table, and each value is a list of rows
  2. The mapping of keys and values to table names and columns is done
     by `add-resources!`"
  [catalog-id {:keys [type title exported parameters tags file line] :as resource} resource-hash persisted?]
  {:pre  [(number? catalog-id)
          (every? string? #{type title})]
   :post [(= (set (keys %)) #{:resource :parameters :parameters_cache})]}
  (let [values {:resource         [[catalog-id resource-hash type title (sutils/to-jdbc-varchar-array tags) exported file line]]
                :parameters       []
                :parameters_cache []}]

    (if persisted?
      values
      (assoc values
        :parameters (for [[key value] parameters]
                      [resource-hash (name key) (sutils/db-serialize value)])
        :parameters_cache [[resource-hash (if parameters (sutils/db-serialize parameters))]]))))

(defn catalog-resources
  "Return a map of the form {hash {:type type :title title :params {key value}}}}"
  [catalog-id]
  (sql/with-query-results result-set
    ["SELECT resource as hash, type, title
                 FROM catalog_resources
                 WHERE catalog_id = ?" catalog-id]
    (zipmap (map #(select-keys % [:type :title]) result-set)
            (map :hash result-set))))

(defn new-params-only
  "Returns a map of not persisted parameters, keyed by hash"
  [persisted-params refs-to-resources refs-to-hashes]
  (reduce-kv (fn [acc resource-ref {:keys [parameters]}]
               (let [resource-hash (get refs-to-hashes resource-ref)]
                 (if (contains? persisted-params (refs-to-hashes resource-ref))
                   acc
                   (assoc acc resource-hash parameters))))
             {} refs-to-resources))

(defn insert-records*
  "Nil/empty safe insert-records, see java.jdbc's insert-records for more "
  [table record-coll]
  (when (seq record-coll)
    (apply sql/insert-records table record-coll)))

(defn add-params!
  "Persists the new parameters found in `refs-to-resources` and populates the
   resource_params_cache."
  [refs-to-resources refs-to-hashes]
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

(defn insert-catalog-resources!
  "Returns a function that accepts a seq of ref keys to insert"
  [catalog-id refs-to-hashes refs-to-resources]
  (fn [refs-to-insert]
    {:pre [(every? resource-ref? refs-to-insert)]}

    (update! (:catalog-volatility metrics) (count refs-to-insert))

    (insert-records*
     :catalog_resources
     (map (fn [resource-ref]
            (let [{:keys [type title exported parameters tags file line] :as resource} (get refs-to-resources resource-ref)]
              {:catalog_id catalog-id
               :resource (get refs-to-hashes resource-ref)
               :type type
               :title title
               :tags (sutils/to-jdbc-varchar-array tags)
               :exported exported
               :file file
               :line line}))
          refs-to-insert))))

(defn delete-catalog-resources!
  "Returns a function accepts old catalog resources that should be deleted."
  [catalog-id]
  (fn [refs-to-delete]
    {:pre [(every? resource-ref? refs-to-delete)]}

    (update! (:catalog-volatility metrics) (count refs-to-delete))

    (doseq [{:keys [type title]} refs-to-delete]
      (sql/delete-rows :catalog_resources ["catalog_id = ? and type = ? and title = ?" catalog-id type title]))))

(defn update-catalog-resources!
  "Returns a function accepting keys that were the same from the old resoruces and the new resources."
  [catalog-id refs-to-hashes old-resources]
  (fn [maybe-updated-refs]
    {:pre [(every? resource-ref? maybe-updated-refs)]}
    (let [updated-refs (remove #(= (get refs-to-hashes %) (get old-resources %)) maybe-updated-refs)]

      (update! (:catalog-volatility metrics) (count updated-refs))

      (doseq [{:keys [type title] :as resource-ref} updated-refs]
        (sql/update-values :catalog_resources
                           ["catalog_id = ? and type = ? and title = ?" catalog-id type title]
                           {:resource (get refs-to-hashes resource-ref)})))))

(defn diff-fn
  "Run clojure.data/diff on `left` and `right`, calling `left-only-fn`, `right-only-fn` and `same-fn` with
   the results of the call. Those functions should always receive a non-nil argument (though possibly empty)."
  [left right left-only-fn right-only-fn same-fn]
  {:pre [(map? left)
         (map? right)
         (fn? left-only-fn)
         (fn? right-only-fn)
         (fn? same-fn)]}
  (let [[left-only right-only same] (data/diff (kitchensink/keyset left) (kitchensink/keyset right))]
    (left-only-fn (or left-only (empty left)))
    (right-only-fn (or right-only (empty right)))
    (same-fn (or same (empty left)))))

(defn add-resources!
  "Persist the given resource and associate it with the given catalog."
  [catalog-id refs-to-resources refs-to-hashes]
  {:pre [(every? resource-ref? (keys refs-to-resources))
         (every? resource-ref? (keys refs-to-hashes))]}
  (let [old-resources (catalog-resources catalog-id)]
    (sql/transaction
     (add-params! refs-to-resources refs-to-hashes)
     (diff-fn old-resources
              refs-to-resources
              (delete-catalog-resources! catalog-id)
              (insert-catalog-resources! catalog-id refs-to-hashes refs-to-resources)
              (update-catalog-resources! catalog-id refs-to-hashes old-resources)))))

(defn catalog-edges-map
  "Return all edges for a given catalog id as a map"
  [certname]
  (sql/with-query-results result-set
    ["SELECT source, target, type FROM edges WHERE certname=?" certname]
    ;; Transform the result-set into a map with [source,target,type] as the key
    ;; and nil as always the value. This just feeds into clojure.data/diff
    ;; better this way.
    (zipmap (map vals result-set)
            (repeat nil))))

(defn delete-edges!
  "Delete edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, eg:

    [[<source> <target> <type>] ...]"
  [certname edges]
  {:pre [(or (coll? edges) (nil? edges))
         (string? certname)]}

  (update! (:catalog-volatility metrics) (count edges))

  (doseq [[source target type] edges]
    ;; This is relatively inefficient. If we have id's for edges, we could do
    ;; this in 1 statement.
    (sql/delete-rows :edges
                     ["certname=? and source=? and target=? and type=?" certname source target type])))

(defn insert-edges!
  "Insert edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, eg:

    [[<source> <target> <type>] ...]"
  [certname edges]
  {:pre [(or (coll? edges) (nil? edges))
         (string? certname)]}
  ;; Insert rows will not safely accept a nil, so abandon this operation
  ;; earlier.
  (when (seq edges)
    (let [rows (for [[source target type] edges]
                 [certname source target type])]

      (update! (:catalog-volatility metrics) (count rows))
      (apply sql/insert-rows :edges rows))))

(defn replace-edges!
  "Persist the given edges in the database

  Each edge is looked up in the supplied resources map to find a
  resource object that corresponds to the edge. We then use that
  resource's hash for persistence purposes.

  For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
  then we'll lookup a resource with that key and use its hash."
  [certname edges refs-to-hashes]
  {:pre [(string? certname)
         (coll? edges)
         (map? refs-to-hashes)]}
  (let [new-edges (zipmap
                   (for [{:keys [source target relationship]} edges
                         :let [source-hash (refs-to-hashes source)
                               target-hash (refs-to-hashes target)
                               type        (name relationship)]]
                     [source-hash target-hash type])
                   (repeat nil))]
    (diff-fn new-edges
             (catalog-edges-map certname)
             #(insert-edges! certname %)
             #(delete-edges! certname %)
             identity)))

(defn update-catalog-hash-match
  "When a new incoming catalog has the same hash as an existing catalog, update metrics
   and the transaction id for the new catalog"
  [catalog-id hash catalog timestamp]
  (inc! (:duplicate-catalog metrics))
  (time! (:catalog-hash-match metrics)
         (update-catalog-metadata! catalog-id hash catalog timestamp)))

(defn update-catalog-associations!
  "Adds/updates/deletes the edges and resources for the given certname"
  [catalog-id {:keys [resources edges certname]} refs-to-hashes]
  (time! (:add-resources metrics)
         (add-resources! catalog-id resources refs-to-hashes))
  (time! (:add-edges metrics)
         (replace-edges! certname edges refs-to-hashes)))

(defn update-catalog-hash-miss
  "New catalogs for a given certname needs to have their metadata, resources and edges updated.  This
   function also outputs debugging related information when `catalog-hash-debug-dir` is not nil"
  [catalog-id hash catalog refs-to-hashes catalog-hash-debug-dir timestamp]

  (inc! (:updated-catalog metrics))

  (when catalog-hash-debug-dir
    (hashdbg/debug-catalog catalog-hash-debug-dir hash catalog))

  (time! (:catalog-hash-miss metrics)
         (update-catalog-metadata! catalog-id hash catalog timestamp)
         (update-catalog-associations! catalog-id catalog refs-to-hashes)))

(defn add-new-catalog
  "Creates new catalog metadata and adds the proper associations for the edges and resources"
  [hash catalog refs-to-hashes timestamp]
  (inc! (:updated-catalog metrics))
  (time! (:add-new-catalog metrics)
         (let [catalog-id (:id (add-catalog-metadata! hash catalog timestamp))]
           (update-catalog-associations! catalog-id catalog refs-to-hashes))))

(defn add-catalog!
  "Persist the supplied catalog in the database, returning its
   similarity hash. `catalog-hash-debug-dir` is an optional path
   that indicates where catalog debugging information should be stored."
  ([catalog]
     (add-catalog! catalog nil (now)))
  ([{:keys [api-version resources edges certname] :as catalog} catalog-hash-debug-dir timestamp]
      {:pre [(number? api-version)
             (coll? edges)
             (map? resources)
             (or (nil? catalog-hash-debug-dir)
                 (string? catalog-hash-debug-dir))]}

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

(defn catalog-newer-than?
  "Returns true if the most current catalog for `certname` is more recent than
  `time`."
  [certname time]
  (let [timestamp (to-timestamp time)]
    (sql/with-query-results result-set
      ["SELECT timestamp FROM catalogs WHERE certname=? ORDER BY timestamp DESC LIMIT 1" certname]
      (if-let [catalog-timestamp (:timestamp (first result-set))]
        (.after catalog-timestamp timestamp)
        false))))

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

;; ## High-level entity manipulation

(defn replace-catalog!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one. `catalog-hash-debug-dir`
  is an optional path that indicates where catalog debugging information
  should be stored."
  ([catalog timestamp]
     (replace-catalog! catalog timestamp nil))
  ([{:keys [certname] :as catalog} timestamp catalog-hash-debug-dir]
     {:pre [(kitchensink/datetime? timestamp)]}
     (time! (:replace-catalog metrics)
            (sql/transaction
             (add-catalog! catalog catalog-hash-debug-dir timestamp)))))

(defn insert-facts!
  "Given a certname and map of fact/value keypairs, insert them into the facts table"
  [certname facts]
  (let [default-row {:certname certname}
        rows        (for [[fact value] facts]
                      (assoc default-row :name fact :value value))]
    (apply sql/insert-records :certname_facts rows)))

(defn add-facts!
  "Given a certname and a map of fact names to values, store records for those
  facts associated with the certname."
  [certname facts timestamp]
  {:pre [(kitchensink/datetime? timestamp)]}
  (sql/insert-record :certname_facts_metadata
                     {:certname certname :timestamp (to-timestamp timestamp)})
  (insert-facts! certname facts))

(defn delete-facts!
  "Delete all the facts (1 arg) or just the fact-names (2 args) for the given certname."
  ([certname]
      {:pre [(string? certname)]}
      (sql/delete-rows :certname_facts_metadata ["certname=?" certname]))
  ([certname fact-names]
     {:pre [(string? certname)]}
     (when (seq fact-names)
       (sql/delete-rows :certname_facts
                        (into [(str "certname=? and name " (jdbc/in-clause fact-names)) certname]  fact-names)))))

(defn cert-fact-map
  "Return all facts and their values for a given certname as a map"
  [certname]
  (sql/with-query-results result-set
    ["SELECT name, value FROM certname_facts WHERE certname=?" certname]
    (zipmap (map :name result-set)
            (map :value result-set))))

(defn update-existing-facts!
  "Issues a SQL Update for `shared-keys` whose value in old-facts
   is different from new-facts"
  [certname old-facts new-facts shared-keys]
  (doseq [k shared-keys]
    (let [old-val (get old-facts k)
          new-val (get new-facts k)]
      (when-not (= old-val new-val)
        (sql/update-values :certname_facts ["certname=? and name=?" certname k] {:value new-val})))))

(defn update-facts!
  "Given a certname, querys the DB for existing facts for that
   certname and will update, delete or insert the facts as necessary
   to match the facts argument."
  [certname facts timestamp]
  (let [old-facts (cert-fact-map certname)]
    (sql/update-values :certname_facts_metadata ["certname=?" certname]
                       {:timestamp (to-timestamp timestamp)})

    (diff-fn old-facts
             facts
             #(delete-facts! certname %)
             #(insert-facts! certname (select-keys facts %))
             #(update-existing-facts! certname old-facts facts %))))

(defn certname-facts-metadata!
  "Return the certname_facts_metadata timestamp for the given certname, nil if not found"
  [certname]
  (sql/with-query-results result-set
    ["SELECT timestamp FROM certname_facts_metadata WHERE certname=? ORDER BY timestamp DESC" certname]
    (:timestamp (first result-set))))

(defn replace-facts!
  "Updates the facts of an existing node, if the facts are newer than the current set of facts.
   Adds all new facts if no existing facts are found. Invoking this function under the umbrella of
   a repeatable read or serializable transaction enforces only one update to the facts of a certname
   can happen at a time.  The first to start the transaction wins.  Subsequent transactions will fail
   as the certname_facts_metadata will have changed while the transaction was in-flight."
  [{:strs [name values]} timestamp]
  {:pre [(string? name)
         (every? string? (keys values))
         (every? string? (vals values))]}
  (time! (:replace-facts metrics)
         (if-let [facts-meta-ts (certname-facts-metadata! name)]
           (when (.before facts-meta-ts (to-timestamp timestamp))
             (update-facts! name values timestamp))
           (add-facts! name values timestamp))))

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

(defn add-report!
  "Add a report and all of the associated events to the database."
  [report timestamp]
  (add-report!* report timestamp true))

(defn delete-reports-older-than!
  "Delete all reports in the database which have an `end-time` that is prior to
  the specified date/time."
  [time]
  {:pre [(kitchensink/datetime? time)]}
  (sql/delete-rows :reports ["end_time < ?" (to-timestamp time)]))

(defmulti db-deprecated?
  "Returns a vector with a boolean indicating if database type and version is
  marked for deprecation. The second element in the vector is a string
  explaining the deprecation."
  (fn [dbtype version] dbtype))

(defmethod db-deprecated? "PostgreSQL"
  [_ version]
  (if (pos? (compare [8 4] version))
    [true "PostgreSQL DB 8.3 and older are deprecated and won't be supported in the future."]
    [false nil]))

(defmethod db-deprecated? :default
  [_ _]
  [false nil])

(defn warn-on-db-deprecation!
  "Get metadata about the current connection and warn if the database we are
  using is deprecated."
  []
  (let [version    (sutils/sql-current-connection-database-version)
        dbtype     (sutils/sql-current-connection-database-name)
        [deprecated? message] (db-deprecated? dbtype version)]
    (when deprecated?
      (log/warn message))))
