;; ## Catalog persistence
;;
;; Catalogs are persisted in a relational database. Roughly speaking,
;; the schema looks like this:
;;
;; * resource_parameters are associated 0 to N resource_metadata (they are
;; deduped across catalogs). It's possible for a resource_param to exist in the
;; database, yet not be associated with a catalog. This is done as a
;; performance optimization.
;;
;; * edges, tags, and classes are associated with a single catalog
;;
;; * catalogs are associated with a single certname
;;
;; * facts are associated with a single certname
;;
;; The standard set of operations on information in the database will
;; likely result in dangling resources and catalogs; to clean these
;; up, it's important to run `garbage-collect!`.

(ns com.puppetlabs.puppetdb.scf.storage
  (:require [com.puppetlabs.puppetdb.catalog :as cat]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.jdbc :as jdbc]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cheshire.core :as json])
  (:use [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [ago days now]]
        [clojure.set :only [map-invert]]
        [clojure.core.memoize :only [memo-lru]]
        [metrics.meters :only (meter mark!)]
        [metrics.counters :only (counter inc! value)]
        [metrics.gauges :only (gauge)]
        [metrics.histograms :only (histogram update!)]
        [metrics.timers :only (timer time!)]))

(defn sql-current-connection-database-name
  "Return the database product name currently in use."
  []
  (.. (sql/find-connection)
      (getMetaData)
      (getDatabaseProductName)))

(defn sql-current-connection-database-version
  "Return the version of the database product currently in use."
  []
  (let [db-metadata (.. (sql/find-connection)
                      (getMetaData))
        major (.getDatabaseMajorVersion db-metadata)
        minor (.getDatabaseMinorVersion db-metadata)]
    [major minor]))

(defn to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object)
         (.createArrayOf connection "varchar"))))

(defmulti sql-array-type-string
  "Returns a string representing the correct way to declare an array
  of the supplied base database type."
  ;; Dispatch based on databsae from the metadata of DB connection at the time
  ;; of call; this copes gracefully with multiple connection types.
  (fn [_] (sql-current-connection-database-name)))

(defmulti sql-array-query-string
  "Returns an SQL fragment representing a query for a single value being
found in an array column in the database.

  `(str \"SELECT ... WHERE \" (sql-array-query-string \"column_name\"))`

The returned SQL fragment will contain *one* parameter placeholder, which
must be supplied as the value to be matched."
  (fn [column] (sql-current-connection-database-name)))

(defmulti sql-as-numeric
  "Returns appropriate db-specific code for converting the given column to a
  number, or to NULL if it is not numeric."
  (fn [_] (sql-current-connection-database-name)))

(defmethod sql-array-type-string "PostgreSQL"
  [basetype]
  (format "%s ARRAY[1]" basetype))

(defmethod sql-array-query-string "PostgreSQL"
  [column]
  (if (pos? (compare (sql-current-connection-database-version) [8 1]))
    (format "ARRAY[?::text] <@ %s" column)
    (format "? = ANY(%s)" column)))

(defmethod sql-array-type-string "HSQL Database Engine"
  [basetype]
  (format "%s ARRAY[%d]" basetype 65535))

(defmethod sql-array-query-string "HSQL Database Engine"
  [column]
  (format "? IN (UNNEST(%s))" column))

(defmethod sql-as-numeric "PostgreSQL"
  [column]
  (format (str "CASE WHEN %s~'^\\d+$' THEN %s::integer "
               "WHEN %s~'^\\d+\\.\\d+$' THEN %s::float "
               "ELSE NULL END")
          column column column column))

(defmethod sql-as-numeric "HSQL Database Engine"
  [column]
  (format (str "CASE WHEN REGEXP_MATCHES(%s, '^\\d+$') THEN CAST(%s AS INTEGER) "
               "WHEN REGEXP_MATCHES(%s, '^\\d+\\.\\d+$') THEN CAST(%s AS FLOAT) "
               "ELSE NULL END")
          column column column column))

(def ns-str (str *ns*))

;; ## Performance metrics
;;
;; ### Timers for catalog storage
;;
;; * `:replace-catalog`: the time it takes to replace the catalog for
;;   a host
;;
;; * `:add-catalog`: the time it takes to persist a catalog
;;
;; * `:add-classes`: the time it takes to persist just a catalog's
;;   classes
;;
;; * `:add-tags`: the time it takes to persist just a catalog's tags
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
;; * `:new-catalog`: how many brand new (non-duplicate) catalogs we've
;;   received
;;
;; * `:duplicate-catalog`: how many duplicate catalogs we've received
;;
;; ### Gauges for catalog storage
;;
;; * `:duplicate-pct`: percentage of incoming catalogs determined to
;;   be duplicates
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
   :add-classes       (timer [ns-str "default" "add-classes"])
   :add-tags          (timer [ns-str "default" "add-tags"])
   :add-resources     (timer [ns-str "default" "add-resources"])
   :add-edges         (timer [ns-str "default" "add-edges"])

   :resource-hashes   (timer [ns-str "default" "resource-hashes"])
   :resource-metadata-hashes   (timer [ns-str "default" "resource-metadata-hashes"])
   :catalog-hash      (timer [ns-str "default" "catalog-hash"])
   :add-catalog       (timer [ns-str "default" "add-catalog-time"])
   :replace-catalog   (timer [ns-str "default" "replace-catalog-time"])

   :gc                (timer [ns-str "default" "gc-time"])
   :gc-catalogs       (timer [ns-str "default" "gc-catalogs-time"])
   :gc-params         (timer [ns-str "default" "gc-params-time"])

   :new-catalog       (counter [ns-str "default" "new-catalogs"])
   :duplicate-catalog (counter [ns-str "default" "duplicate-catalogs"])
   :duplicate-pct     (gauge [ns-str "default" "duplicate-pct"]
                             (let [dupes (value (:duplicate-catalog metrics))
                                   new   (value (:new-catalog metrics))]
                               (float (utils/quotient dupes (+ dupes new)))))

   :replace-facts     (timer [ns-str "default" "replace-facts-time"])
   })

(defn db-serialize
  "Serialize `value` into a form appropriate for querying against a
  serialized database column."
  [value]
  (json/generate-string (if (map? value)
                          (into (sorted-map) value)
                          value)))

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
  {:pre  [(utils/datetime? time)]
   :post [(coll? %)]}
  (let [ts (to-timestamp time)]
    (map :name (jdbc/query-to-vec "SELECT c.name FROM certnames c
                                   LEFT OUTER JOIN certname_catalogs cc ON c.name=cc.certname
                                   LEFT OUTER JOIN certname_facts_metadata fm ON c.name=fm.certname
                                   WHERE c.deactivated IS NULL
                                   AND (cc.timestamp IS NULL OR cc.timestamp < ?)
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

(defn activate-node!
  "Reactivate the given host"
  [certname]
  {:pre [(string? certname)]}
  (sql/update-values :certnames
                     ["name=?" certname]
                     {:deactivated nil}))

(defn maybe-activate-node!
  "Reactivate the given host, only if it was deactivated before `time`.
  Returns true if the node is activated, or if it was already active."
  [certname time]
  {:pre [(string? certname)]}
  (let [timestamp (to-timestamp time)
        replaced  (sql/update-values :certnames
                                     ["name=? AND (deactivated<? OR deactivated IS NULL)" certname timestamp]
                                     {:deactivated nil})]
    (pos? (first replaced))))

(defn add-catalog-metadata!
  "Given some catalog metadata, persist it in the db"
  [hash api-version catalog-version]
  {:pre [(string? hash)
         (number? api-version)
         (string? catalog-version)]}
  (sql/insert-record :catalogs {:hash            hash
                                :api_version     api-version
                                :catalog_version catalog-version}))

(defn catalog-exists?
  "Returns a boolean indicating whether or not the given catalog exists in the db"
  [hash]
  {:pre [hash]}
  (sql/with-query-results result-set
    ["SELECT 1 FROM catalogs WHERE hash=? LIMIT 1" hash]
    (pos? (count result-set))))

(defn add-classes!
  "Given a catalog-hash and a list of classes, persist them in the db"
  [catalog-hash classes]
  {:pre [(string? catalog-hash)
         (coll? classes)]}
  (let [default-row {:catalog catalog-hash}
        classes     (map #(assoc default-row :name %) classes)]
    (apply sql/insert-records :classes classes)))

(defn add-tags!
  "Given a catalog-hash and a list of tags, persist them in the db"
  [catalog-hash tags]
  {:pre [(string? catalog-hash)
         (coll? tags)]}
  (let [default-row {:catalog catalog-hash}
        tags        (map #(assoc default-row :name %) tags)]
    (apply sql/insert-records :tags tags)))

(defn resource-params-exist?
  "Given a collection of param hashes, return the subset that already exist in
  the database."
  [resource-hashes]
  {:pre  [(coll? resource-hashes)
          (every? string? resource-hashes)]
   :post [(set? %)]}
  (let [qmarks     (str/join "," (repeat (count resource-hashes) "?"))
        query      (format "SELECT DISTINCT resource FROM resource_params WHERE resource IN (%s)" qmarks)
        sql-params (vec (cons query resource-hashes))]
    (sql/with-query-results result-set
      sql-params
      (set (map :resource result-set)))))

(defn resource-metadata-exist?
  "Given a collection of tag hashes, return the subset that already exist in
  the database."
  [resource-metadata-hashes]
  {:pre  [(coll? resource-metadata-hashes)
          (every? string? resource-metadata-hashes)]
   :post [(set? %)]}
  (let [qmarks     (str/join "," (repeat (count resource-metadata-hashes) "?"))
        query      (format "SELECT DISTINCT hash FROM resource_metadata WHERE hash IN (%s)" qmarks)
        sql-params (vec (cons query resource-metadata-hashes))]
    (sql/with-query-results result-set
      sql-params
      (set (map :hash result-set)))))

(defn resource-tags-exist?
  "Given a collection of tag hashes, return the subset that already exist in
  the database."
  [resource-tag-hashes]
  {:pre [(coll? resource-tag-hashes)
         (every? string? resource-tag-hashes)]
   :post [(set? %)]}
  (let [qmarks     (str/join "," (repeat (count resource-tag-hashes) "?"))
        query      (format "SELECT DISTINCT hash FROM resource_tags WHERE hash IN (%s)" qmarks)
        sql-params (vec (cons query resource-tag-hashes))]
    (sql/with-query-results result-set
      sql-params
      (set (map :hash result-set)))))

(def hash-cache
  (atom {}))

;; Size of the cache is based on the number of unique resources in a
;; "medium" site persona
(defn resource-hash*
  [& things]
  (if-let [result (@hash-cache things)]
    result
    (let [result (apply utils/sha1 things)]
      (swap! hash-cache assoc things result)
      result)))

(defn resource-params-hash
  "Compute a hash for a given resource that will uniquely identify its set of
  parameters. This hash is used for deduplicating entries in the
  resource_params table, as referenced by the catalog_resources table."
  [{:keys [parameters] :as resource}]
  {:pre  [(map? resource)]
   :post [(string? %)]}
  (resource-hash* (sort parameters)))

(defn resource-metadata-hash
  "Compute a hash for the given resource's metadata. This is used for
  deduplication of resource metadata (excluding tags)."
  [{:keys [type title exported file line] :as resource}]
  {:pre [(map? resource)]
   :post [(string? %)]}
  (resource-hash* type title exported file line))

(defn resource-tags-hash
  "Compute a hash for the given resource's tag, used for tag deduplication."
  [{:keys [tags] :as resource}]
  {:pre [(map? resource)]
   :post [(string? %)]}
  (resource-hash* (sort tags)))

(defn compute-resource-hashes
  "Compute the set of hashes for a resource. This includes the params hash,
  metadata hash, and tags hash. The hashes are returned as a map."
  [resource]
  {:pre [(map? resource)]
   :post [(map? %)
          (= (utils/keyset %) #{:params-hash :metadata-hash :tags-hash})
          (every? string? (vals %))]}
  {:params-hash (resource-params-hash resource)
   :metadata-hash (resource-metadata-hash resource)
   :tags-hash (resource-tags-hash resource)})

(defn- resource->values
  "Given a catalog-hash, a resource, and a truthy value indicating
  whether or not the indicated resource already exists somewhere in
  the database, return a map representing the set of database rows
  pending insertion.

  The result map has the following format:

    {:hashes [[<catalog hash> <resource hash>] ...]
     :metadata [[<resouce hash> <type> <title> <exported?> <sourcefile> <sourceline>] ...]
     :parameters [[<resource hash> <name> <value>] ...]
     :tags [[<resource hash> <tag>] ...]}

  The result map format may seem arbitrary and confusing, but its best
  to think about it in 2 ways:

  1. Each key corresponds to a table, and each value is a list of rows
  2. The mapping of keys and values to table names and columns is done
     by `add-resources!`"
  [catalog-hash {:keys [type title exported parameters tags file line] :as resource} {:keys [params-hash metadata-hash tags-hash]} params-persisted? metadata-persisted? tags-persisted?]
  {:pre  [(every? string? [catalog-hash type title params-hash metadata-hash tags-hash])]
   :post [(= (set (keys %)) #{:metadata :tags :parameters :catalog_resources})]}
  (let [catalog-resource-cols [[catalog-hash params-hash metadata-hash tags-hash]]
        tag-cols              (if-not tags-persisted?
                                [[tags-hash (to-jdbc-varchar-array tags)]])
        metadata-cols         (if-not metadata-persisted?
                                [[metadata-hash type title exported file line]])
        param-cols            (if-not params-persisted?
                                (for [[key value] parameters]
                                  [params-hash (name key) (db-serialize value)]))]
    {:catalog_resources catalog-resource-cols
     :tags tag-cols
     :metadata metadata-cols
     :parameters param-cols}))

(defn add-resource-metadata!
  [resources-to-hashes]
  (let [sql "INSERT INTO resource_metadata (hash,type,title,exported,sourcefile,sourceline) VALUES (?,?,?,?,?,?)"
        metadata-persisted? (resource-metadata-exist? (map :metadata-hash (vals resources-to-hashes)))
        rows (set (for [[{:keys [type title exported file line]} {:keys [metadata-hash]}] resources-to-hashes
                        :when (not (metadata-persisted? metadata-hash))]
                    [metadata-hash type title exported file line]))]
    (when (seq rows)
      (apply sql/do-prepared sql rows))))

(defn add-resource-params!
  [resources-to-hashes]
  (let [sql "INSERT INTO resource_params (resource,name,value) VALUES (?,?,?)"
        params-persisted? (resource-params-exist? (map :params-hash (vals resources-to-hashes)))
        rows (set
               (apply concat (for [[{:keys [parameters]} {:keys [params-hash]}] resources-to-hashes
                                   :when (not (params-persisted? params-hash))]
                               (for [[key value] parameters]
                                 [params-hash (name key) (db-serialize value)]))))]
    (when (seq rows)
      (apply sql/do-prepared sql rows))))

(defn add-resource-tags!
  [resources-to-hashes]
  (let [sql "INSERT INTO resource_tags (hash,tags) VALUES (?,?)"
        tags-persisted? (resource-tags-exist? (map :tags-hash (vals resources-to-hashes)))
        ;; We need the two-step process here because jdbc array objects don't
        ;; properly unique-ify in sets. So first we make the elemtns unique,
        ;; then turn them into jdbc arrays.
        rows (set (for [[{:keys [tags]} {:keys [tags-hash]}] resources-to-hashes
                        :when (not (tags-persisted? tags-hash))]
                    [tags-hash tags]))
        rows (for [[tags-hash tags] rows]
               [tags-hash (to-jdbc-varchar-array tags)])]
    (when (seq rows)
      (apply sql/do-prepared sql rows))))

(defn add-catalog-resources!
  [catalog-hash resources-to-hashes]
  (let [sql "INSERT INTO catalog_resources (catalog,params,metadata,tags) VALUES (?,?,?,?)"
        rows (set (for [[{:keys [tags]} {:keys [metadata-hash params-hash tags-hash]}] resources-to-hashes]
                    [catalog-hash params-hash metadata-hash tags-hash]))]
    (when (seq rows)
      (apply sql/do-prepared sql rows))))

(defn add-resources!
  "Persist the given resource and associate it with the given catalog."
  [catalog-hash resources-to-hashes]
  (sql/transaction
    (add-resource-metadata! resources-to-hashes)
    (add-resource-params! resources-to-hashes)
    (add-resource-tags! resources-to-hashes)
    (add-catalog-resources! catalog-hash resources-to-hashes)))

(defn edge-identity-string
  "Compute a stably-sorted string for the given edge that will
  uniquely identify it within a population."
  [edge]
  {:pre  [(map? edge)]
   :post [(string? %)]}
  (-> (into (sorted-map) edge)
      (assoc :source (into (sorted-map) (:source edge)))
      (assoc :target (into (sorted-map) (:target edge)))
      (pr-str)))

(defn add-edges!
  "Persist the given edges in the database

  Each edge is looked up in the supplied resources map to find a
  resource object that corresponds to the edge. We then use that
  resource's hash for persistence purposes.

  For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
  then we'll lookup a resource with that key and use its hash."
  [catalog-hash edges refs-to-resources resources-to-hashes]
  {:pre [(string? catalog-hash)
         (coll? edges)
         (map? resources-to-hashes)]}
  (let [resource-metadata (comp :metadata-hash resources-to-hashes refs-to-resources)
        the-sql "INSERT INTO edges (catalog,source,target,type) VALUES (?,?,?,?)"
        rows    (for [{:keys [source target relationship]} edges
                      :let [source-hash (resource-metadata source)
                            target-hash (resource-metadata target)
                            type        (name relationship)]]
                  [catalog-hash source-hash target-hash type])]
    (apply sql/do-prepared the-sql rows)))

(defn catalog-similarity-hash
  "Compute a hash for the given catalog's content

  This hash is useful for situations where you'd like to determine
  whether or not two catalogs contain the same things (edges,
  resources, tags, classes, etc).

  Note that this hash *cannot* be used to uniquely identify a catalog
  within a population! This is because we're only examing a subset of
  a catalog's attributes. For example, two otherwise identical
  catalogs with different :version's would have the same similarity
  hash, but don't represent the same catalog across time."
  ([{:keys [certname classes tags resources edges] :as catalog}]
   (let [resources (vals resources)
         resources-to-hashes (zipmap resources (map compute-resource-hashes resources))]
     (catalog-similarity-hash catalog resources-to-hashes)))
  ([{:keys [certname classes tags resources edges] :as catalog} resources-to-hashes]
   ;; deepak: This could probably be coded more compactly by just
   ;; dissociating the keys we don't want involved in the computation,
   ;; but I figure that for safety's sake, it's better to be very
   ;; explicit about the exact attributes of a catalog that we care
   ;; about when we think about "uniqueness".
   (-> (sorted-map)
     (assoc :classes (sort classes))
     (assoc :tags (sort tags))
     (assoc :resources (sort (flatten (for [[resource hashes] resources-to-hashes] (vals hashes)))))
     (assoc :edges (sort (map edge-identity-string edges)))
     (utils/sha1))))

(defn add-catalog!
  "Persist the supplied catalog in the database, returning its
  similarity hash"
  [{:keys [api-version version resources classes edges tags] :as catalog}]
  {:pre [(number? api-version)
         (every? coll? [classes tags edges])
         (map? resources)]}

  (time! (:add-catalog metrics)
         (let [resources-to-hashes (time! (:resource-hashes metrics)
                                          (let [resources (vals resources)]
                                            (zipmap resources (map compute-resource-hashes resources))))
               catalog-hash            (time! (:catalog-hash metrics)
                                              (catalog-similarity-hash catalog resources-to-hashes))]

           (sql/transaction
             (let [exists? (catalog-exists? catalog-hash)]

               (when exists?
                 (inc! (:duplicate-catalog metrics)))

               (when-not exists?
                 (inc! (:new-catalog metrics))
                 (add-catalog-metadata! catalog-hash api-version version)
                 (time! (:add-classes metrics)
                        (add-classes! catalog-hash classes))
                 (time! (:add-tags metrics)
                        (add-tags! catalog-hash tags))
                 (time! (:add-resources metrics)
                        (add-resources! catalog-hash resources-to-hashes))
                 (time! (:add-edges metrics)
                        (add-edges! catalog-hash edges resources resources-to-hashes)))))

           catalog-hash)))

(defn delete-catalog!
  "Remove the catalog identified by the following hash"
  [catalog-hash]
  (sql/delete-rows :catalogs ["hash=?" catalog-hash]))

(defn associate-catalog-with-certname!
  "Creates a relationship between the given certname and catalog"
  [catalog-hash certname timestamp]
  (sql/insert-record :certname_catalogs {:certname certname :catalog catalog-hash :timestamp (to-timestamp timestamp)}))

(defn dissociate-catalog-with-certname!
  "Breaks the relationship between the given certname and catalog"
  [catalog-hash certname]
  (sql/delete-rows :certname_catalogs ["certname=? AND catalog=?" certname catalog-hash]))

(defn dissociate-all-catalogs-for-certname!
  "Breaks all relationships between `certname` and any catalogs"
  [certname]
  (sql/delete-rows :certname_catalogs ["certname=?" certname]))

(defn catalogs-for-certname
  "Returns a collection of catalog-hashes associated with the given
  certname"
  [certname]
  (sql/with-query-results result-set
    ["SELECT catalog FROM certname_catalogs WHERE certname=?" certname]
    (vec (map :catalog result-set))))

(defn catalog-newer-than?
  "Returns true if the most current catalog for `certname` is more recent than
  `time`."
  [certname time]
  (let [timestamp (to-timestamp time)]
    (sql/with-query-results result-set
      ["SELECT timestamp FROM certname_catalogs WHERE certname=? ORDER BY timestamp DESC LIMIT 1" certname]
      (if-let [catalog-timestamp (:timestamp (first result-set))]
        (.after catalog-timestamp timestamp)
        false))))

(defn facts-newer-than?
  "Returns true if the most current facts for `certname` are more recent than
  `time`."
  [certname time]
  (let [timestamp (to-timestamp time)]
    (sql/with-query-results result-set
      ["SELECT timestamp FROM certname_facts_metadata WHERE certname=? ORDER BY timestamp DESC LIMIT 1" certname]
      (if-let [facts-timestamp (:timestamp (first result-set))]
        (.after facts-timestamp timestamp)
        false))))

;; ## Database compaction

(defn delete-unassociated-catalogs!
  "Remove any catalogs that aren't associated with a certname"
  []
  (time! (:gc-catalogs metrics)
         (sql/delete-rows :catalogs ["NOT EXISTS (SELECT * FROM certname_catalogs cc WHERE cc.catalog=catalogs.hash)"])))

(defn delete-unassociated-params!
  "Remove any resources that aren't associated with a catalog"
  []
  (time! (:gc-params metrics)
         (sql/delete-rows :resource_params ["NOT EXISTS (SELECT * FROM catalog_resources cr WHERE cr.params=resource_params.resource)"])))

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  []
  (time! (:gc metrics)
         (sql/transaction
          (delete-unassociated-catalogs!)
          (delete-unassociated-params!))))

;; ## High-level entity manipulation

(defn store-catalog-for-certname!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one."
  [{:keys [certname] :as catalog} timestamp]
  {:pre [(utils/datetime? timestamp)]}
  (time! (:replace-catalog metrics)
         (sql/transaction
           (let [catalog-hash (add-catalog! catalog)]
             (associate-catalog-with-certname! catalog-hash certname timestamp)))))

(defn add-facts!
  "Given a certname and a map of fact names to values, store records for those
facts associated with the certname."
  [certname facts timestamp]
  {:pre [(utils/datetime? timestamp)]}
  (let [default-row {:certname certname}
        rows        (for [[fact value] facts]
                      (assoc default-row :fact fact :value value))]
    (sql/insert-record :certname_facts_metadata
                       {:certname certname :timestamp (to-timestamp timestamp)})
    (apply sql/insert-records :certname_facts rows)))

(defn delete-facts!
  "Delete all the facts for the given certname."
  [certname]
  {:pre [(string? certname)]}
  (sql/delete-rows :certname_facts_metadata ["certname=?" certname]))

(defn replace-facts!
  [{:strs [name values]} timestamp]
  {:pre [(string? name)
         (every? string? (keys values))
         (every? string? (vals values))]}
  (time! (:replace-facts metrics)
         (sql/transaction
          (delete-facts! name)
          (add-facts! name values timestamp))))
