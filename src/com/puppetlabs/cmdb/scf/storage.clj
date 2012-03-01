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
;; * edges, tags, and classes are associated with a single catalog
;;
;; * catalogs are associated with a single certname
;;
;; * facts are associated with a single certname
;;
;; The standard set of operations on information in the database will
;; likely result in dangling resources and catalogs; to clean these
;; up, it's important to run `garbage-collect!`.

(ns com.puppetlabs.cmdb.scf.storage
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.utils :as utils]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [cheshire.core :as json])
  (:use [metrics.meters :only (meter mark!)]
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


(defmethod sql-array-type-string "PostgreSQL"
  [basetype]
  (format "%s ARRAY" basetype))

(defmethod sql-array-query-string "PostgreSQL"
  [column]
  (format "? = ANY(%s)" column))

(defmethod sql-array-type-string "HSQL Database Engine"
  [basetype]
  (format "%s ARRAY[%d]" basetype 65535))

(defmethod sql-array-query-string "HSQL Database Engine"
  [column]
  (format "? IN (UNNEST(%s))" column))

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

(defn add-catalog-metadata!
  "Given some catalog metadata, persist it in the db"
  [hash api-version catalog-version]
  {:pre [(string? hash)
         (number? api-version)
         (string? catalog-version)]}
  (sql/insert-record :catalogs {:hash hash
                                :api_version api-version
                                :catalog_version catalog-version}))

(defn update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [hash api-version catalog-version]
  {:pre [(string? hash)
         (number? api-version)
         (string? catalog-version)]}
  (sql/update-values :catalogs
                     ["hash=?" hash]
                     {:api_version api-version
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

(defn resource-exists?
  "Returns a boolean indicating whether or not the given resource exists in the db"
  [resource-hash]
  {:pre [(string? resource-hash)]}
  (sql/with-query-results result-set
    ["SELECT 1 FROM resource_params WHERE resource=? LIMIT 1" resource-hash]
    (pos? (count result-set))))

(defn resource-identity-string
  "Compute a stably-sorted, string representation of the given
  resource that will uniquely identify it within a population."
  [{:keys [type title parameters] :as resource}]
  {:pre  [(map? resource)]
   :post [(string? %)]}
  (pr-str [type title (sort parameters)]))

(defn resource-identity-hash
  "Compute a hash for a given resource that will uniquely identify it
  within a population.

  A resource is represented by a map that itself contains maps and
  sets in addition to scalar values. We want two resources with the
  same attributes to be equal for the purpose of deduping, therefore
  we need to make sure that when generating a hash for a resource we
  look at a stably-sorted view of the resource. Thus, we need to sort
  both the resource as a whole as well as any nested collections it
  contains."
  [resource]
  {:pre  [(map? resource)]
   :post [(string? %)]}
  (-> (resource-identity-string resource)
      (utils/utf8-string->sha1)))

(defn- resource->values
  "Given a catalog-hash and a resource, return a map representing the
  set of database rows pending insertion.

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
  [catalog-hash {:keys [type title exported parameters tags file line] :as resource} resource-hash]
  {:pre  [(every? string? #{catalog-hash type title})]
   :post [(= (set (keys %)) #{:resource :parameters})]}
  (let [persisted?    (resource-exists? resource-hash)
        values        {:resource [[catalog-hash resource-hash type title (to-jdbc-varchar-array tags) exported file line]]
                       :parameters []}]

    (if persisted?
      values
      (assoc values :parameters (for [[name value] parameters]
                                  [resource-hash name (db-serialize value)])))))

(defn add-resources!
  "Persist the given resource and associate it with the given catalog."
  [catalog-hash refs-to-resources refs-to-hashes]
  (let [resource-values   (for [[ref resource] refs-to-resources]
                            (resource->values catalog-hash resource (refs-to-hashes ref)))
        lookup-table      [[:resource "INSERT INTO catalog_resources (catalog,resource,type,title,tags,exported,sourcefile,sourceline) VALUES (?,?,?,?,?,?,?,?)"]
                           [:parameters "INSERT INTO resource_params (resource,name,value) VALUES (?,?,?)"]]]
    (sql/transaction
     (doseq [[lookup the-sql] lookup-table
             :let [param-sets (remove empty? (mapcat lookup resource-values))]
             :when (not (empty? param-sets))]
       (apply sql/do-prepared the-sql param-sets)))))

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

(defn edge-identity-hash
  "Compute a hash for a given edge that will uniquely identify it
  within a population."
  [edge]
  {:pre  [(map? edge)]
   :post [(string? %)]}
  (-> (edge-identity-string edge)
      (utils/utf8-string->sha1)))

(defn add-edges!
  "Persist the given edges in the database

  Each edge is looked up in the supplied resources map to find a
  resource object that corresponds to the edge. We then use that
  resource's hash for persistence purposes.

  For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
  then we'll lookup a resource with that key and use its hash."
  [catalog-hash edges refs-to-hashes]
  {:pre [(string? catalog-hash)
         (coll? edges)
         (map? refs-to-hashes)]}
  (let [the-sql "INSERT INTO edges (catalog,source,target,type) VALUES (?,?,?,?)"
        rows    (for [{:keys [source target relationship]} edges
                      :let [source-hash (refs-to-hashes source)
                            target-hash (refs-to-hashes target)
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
  [{:keys [certname classes tags resources edges] :as catalog}]
  ;; deepak: This could probably be coded more compactly by just
  ;; dissociating the keys we don't want involved in the computation,
  ;; but I figure that for safety's sake, it's better to be very
  ;; explicit about the exact attributes of a catalog that we care
  ;; about when we think about "uniqueness".
  (-> (sorted-map)
      (assoc :certname certname)
      (assoc :classes (sort classes))
      (assoc :tags (sort tags))
      (assoc :resources (sort (for [[ref {:keys [type title tags exported file line]}] resources]
                              [type title (sort tags) exported file line])))
      (assoc :edges (sort (map edge-identity-string edges)))
      (pr-str)
      (utils/utf8-string->sha1)))

(defn add-catalog!
  "Persist the supplied catalog in the database, returning its
  similarity hash"
  [{:keys [api-version version resources classes edges tags] :as catalog}]
  {:pre [(number? api-version)
         (every? coll? [classes tags edges])
         (map? resources)]}

  (time! (:add-catalog metrics)
    (let [resource-hashes (time! (:resource-hashes metrics)
                            (doall
                              (map resource-identity-hash (vals resources))))
          hash (time! (:catalog-hash metrics)
                 (catalog-similarity-hash catalog))]

     (sql/transaction
      (let [exists? (catalog-exists? hash)]

        (when exists?
          (inc! (:duplicate-catalog metrics))
          (update-catalog-metadata! hash api-version version))

        (when-not exists?
          (inc! (:new-catalog metrics))
          (add-catalog-metadata! hash api-version version)
          (time! (:add-classes metrics)
            (add-classes! hash classes))
          (time! (:add-tags metrics)
            (add-tags! hash tags))
          (let [refs-to-hashes (zipmap (keys resources) resource-hashes)]
            (time! (:add-resources metrics)
              (add-resources! hash resources refs-to-hashes))
            (time! (:add-edges metrics)
              (add-edges! hash edges refs-to-hashes))))))

     hash)))

(defn delete-catalog!
  "Remove the catalog identified by the following hash"
  [catalog-hash]
  (sql/delete-rows :catalogs ["hash=?" catalog-hash]))

(defn associate-catalog-with-certname!
  "Creates a relationship between the given certname and catalog"
  [catalog-hash certname]
  (sql/insert-record :certname_catalogs {:certname certname :catalog catalog-hash}))

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
    (into [] (map :catalog result-set))))

;; ## Database compaction

(defn delete-unassociated-catalogs!
  "Remove any catalogs that aren't associated with a certname"
  []
  (time! (:gc-catalogs metrics)
   (sql/delete-rows :catalogs ["hash NOT IN (SELECT catalog FROM certname_catalogs)"])))

(defn delete-unassociated-params!
  "Remove any resources that aren't associated with a catalog"
  []
  (time! (:gc-params metrics)
   (sql/delete-rows :resource_params ["resource NOT IN (SELECT resource FROM catalog_resources)"])))

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  []
  (time! (:gc metrics)
   (sql/transaction
    (delete-unassociated-catalogs!)
    (delete-unassociated-params!))))

;; ## High-level entity manipulation

(defn replace-catalog!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one."
  [{:keys [certname] :as catalog}]
  (time! (:replace-catalog metrics)
   (sql/transaction
    (let [catalog-hash (add-catalog! catalog)]
      (dissociate-all-catalogs-for-certname! certname)
      (associate-catalog-with-certname! catalog-hash certname)))))

(defn add-facts!
  "Given a certname and a map of fact names to values, store records for those
facts associated with the certname."
  [certname facts]
  (let [default-row {:certname certname}
        rows (for [[fact value] facts]
               (assoc default-row :fact fact :value value))]
    (apply sql/insert-records :certname_facts rows)))

(defn delete-facts!
  "Delete all the facts for the given certname."
  [certname]
  {:pre [(string? certname)]}
  (sql/delete-rows :certname_facts ["certname=?" certname]))

(defn replace-facts!
  [certname facts]
  (time! (:replace-facts metrics)
   (sql/transaction
    (delete-facts! certname)
    (add-facts! certname facts))))
