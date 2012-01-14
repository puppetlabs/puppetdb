;; # Catalog persistence
;;
;; Catalogs are persisted in a relational database. Roughly speaking,
;; the schema looks like this:
;;
;; * resource parameters are associated with a single resource
;;
;; * resources are associated 0 to N catalogs (they are deduped across
;;   catalogs). It's possible for a resource to exist in the database,
;;   yet not be associated with a catalog. This is done as a
;;   performance optimization.
;;
;; * edges, tags, and classes are associated with a single catalog
;;
;; * catalogs are associated with a single certname
;;
;; The standard set of operations on information in the database will
;; likely result in dangling resources and catalogs; to clean these
;; up, it's important to run `garbage-collect!`.
;;
;; * * * * *

(ns com.puppetlabs.cmdb.scf.storage
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.utils :as utils]
            [clojure.java.jdbc :as sql]
            [clojure.contrib.logging :as log]
            [digest]
            [cheshire.core :as json]))

(defn db-serialize
  "Serialize `value` into a form appropriate for querying
against a serialized database column."
  [value]
  (json/generate-string (if (map? value)
                      (into (sorted-map) value)
                      value)))

;; ## Database schema
;;
;; _Note_: In the longer term this should be replaced with some sort
;; of migration- style database management library, or some other
;; model that supports upgrades in the field nicely, but this will do
;; for now.

(defn initialize-store
  "Create initial database state"
  []
  (sql/create-table :certnames
                    ["name" "TEXT" "PRIMARY KEY"])

  (sql/create-table :catalogs
                    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
                    ["api_version" "INT" "NOT NULL"]
                    ["catalog_version" "TEXT" "NOT NULL"])

  (sql/create-table :certname_catalogs
                    ["certname" "TEXT" "UNIQUE" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["catalog" "VARCHAR(40)" "UNIQUE" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["PRIMARY KEY (certname, catalog)"])

  (sql/create-table :tags
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (catalog, name)"])

  (sql/create-table :classes
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (catalog, name)"])

  (sql/create-table :resources
                    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
                    ["type" "TEXT" "NOT NULL"]
                    ["title" "TEXT" "NOT NULL"]
                    ["exported" "BOOLEAN" "NOT NULL"]
                    ["sourcefile" "TEXT"]
                    ["sourceline" "INT"])

  (sql/create-table :catalog_resources
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["PRIMARY KEY (catalog, resource)"])

  (sql/create-table :resource_params
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["value" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :resource_tags
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :edges
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["source" "TEXT" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["target" "TEXT" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["type" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (catalog, source, target, type)"])

  (sql/do-commands
   "CREATE INDEX idx_catalogs_hash ON catalogs(hash)")

  (sql/do-commands
   "CREATE INDEX idx_certname_catalogs_certname ON certname_catalogs(certname)")

  (sql/do-commands
   "CREATE INDEX idx_resources_type ON resources(type)")

  (sql/do-commands
   "CREATE INDEX idx_resources_params_resource ON resource_params(resource)")

  (sql/do-commands
   "CREATE INDEX idx_resources_params_name ON resource_params(name)"))

;; # Entity manipulation

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
    ["SELECT 1 FROM resources WHERE hash=? LIMIT 1" resource-hash]
    (pos? (count result-set))))

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
  (-> ; Sort the entire resource map
      (into (sorted-map) resource)
      ; Sort the parameter map
      (assoc :parameters (into (sorted-map) (:parameters resource)))
      ; Sort the set of tags
      (assoc :tags (into (sorted-set) (:tags resource)))
      (pr-str)
      (digest/sha-1)))

(defn add-resource!
  "Given a certname and a single resource, persist that resource and its parameters"
  [catalog-hash {:keys [type title exported parameters tags file line] :as resource}]
  {:pre [(every? string? #{type title})]}

  (let [resource-hash (resource-identity-hash resource)
        persisted?    (resource-exists? resource-hash)
        connection    (sql/find-connection)]

    (when-not persisted?
      ; Add to resources table
      (sql/insert-record :resources {:hash resource-hash :type type :title title :exported exported :sourcefile file :sourceline line})

      ; Build up a list of records for insertion
      (let [records (for [[name value] parameters]
                      ; Parameter values are represented as serialized strings,
                      ; for ease of comparison.
                      (let [value (db-serialize value)]
                        {:resource resource-hash :name name :value value}))]

        ; ...and insert them
        (apply sql/insert-records :resource_params records))

      ; Add rows for each of the resource's tags
      (let [records (for [tag tags] {:resource resource-hash :name tag})]
        (apply sql/insert-records :resource_tags records)))

    ;; Insert pointer into certname => resource map
    (sql/insert-record :catalog_resources {:catalog catalog-hash :resource resource-hash})))

(defn edge-identity-hash
  "Compute a hash for a given edge that will uniquely identify it
  within a population."
  [edge]
  {:pre  [(map? edge)]
   :post [(string? %)]}
  (-> (into (sorted-map) edge)
      (assoc :source (into (sorted-map) (:source edge)))
      (assoc :target (into (sorted-map) (:target edge)))
      (pr-str)
      (digest/sha-1)))

(defn add-edges!
  "Persist the given edges in the database

  Each edge is looked up in the supplied resources map to find a
  resource object that corresponds to the edge. We then use that
  resource's hash for persistence purposes.

  For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
  then we'll lookup a resource with that key and use its hash."
  [catalog-hash edges resources]
  {:pre [(string? catalog-hash)
         (coll? edges)
         (map? resources)]}
  (let [rows  (for [{:keys [source target relationship]} edges
                    :let [source-hash (resource-identity-hash (resources source))
                          target-hash (resource-identity-hash (resources target))
                          type        (name relationship)]]
                {:catalog catalog-hash :source source-hash :target target-hash :type type})]
    (apply sql/insert-records :edges rows)))

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
      (assoc :resources (sort (map resource-identity-hash (vals resources))))
      (assoc :edges (sort (map edge-identity-hash edges)))
      (pr-str)
      (digest/sha-1)))

(defn add-catalog!
  "Persist the supplied catalog in the database, returning its
  similarity hash"
  [{:keys [api-version version resources classes edges tags] :as catalog}]
  {:pre [(number? api-version)
         (every? coll? #{classes tags edges})
         (map? resources)]}

  (let [hash (catalog-similarity-hash catalog)]

    (sql/transaction
     (let [exists? (catalog-exists? hash)]

       (when exists?
         (update-catalog-metadata! hash api-version version))

       (when-not exists?
         (add-catalog-metadata! hash api-version version)
         (add-classes! hash classes)
         (add-tags! hash tags)
         (doseq [resource (vals resources)]
           (add-resource! hash resource))
         (add-edges! hash edges resources))))

    hash))

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

(defn delete-unassociated-catalogs!
  "Remove any catalogs that aren't associated with a certname"
  []
  (sql/delete-rows :catalogs ["hash NOT IN (SELECT catalog FROM certname_catalogs)"]))

(defn delete-unassociated-resources!
  "Remove any resources that aren't associated with a catalog"
  []
  (sql/delete-rows :resources ["hash NOT IN (SELECT resource FROM catalog_resources)"]))

(defn catalogs-for-certname
  "Returns a collection of catalog-hashes associated with the given
  certname"
  [certname]
  (sql/with-query-results result-set
    ["SELECT catalog FROM certname_catalogs WHERE certname=?" certname]
    (into [] (map :catalog result-set))))

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  []
  (sql/transaction
   (delete-unassociated-catalogs!)
   (delete-unassociated-resources!)))

(defn replace-catalog!
  "Given a catalog, replace the current catalog, if any, for its
  associated host with the supplied one."
  [{:keys [certname] :as catalog}]
  (sql/transaction
   (let [catalog-hash (add-catalog! catalog)]
     (dissociate-all-catalogs-for-certname! certname)
     (associate-catalog-with-certname! catalog-hash certname))))
