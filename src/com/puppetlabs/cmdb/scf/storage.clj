(ns com.puppetlabs.cmdb.scf.storage
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.utils :as utils]
            [clojure.java.jdbc :as sql]
            [digest]))

(defmulti sql-array-type-string
  "Returns a string representing the correct way to declare an array
  of the supplied base database type."
  ; Dispatch based on type of DB connection at the time of call
  (fn [_] (class (sql/find-connection))))

(defmulti to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  ; Dispatch based on type of DB connection at the time of call
  (fn [_] (class (sql/find-connection))))

(defmethod sql-array-type-string org.postgresql.PGConnection
  [basetype]
  (format "%s ARRAY" basetype))

(defmethod to-jdbc-varchar-array org.postgresql.PGConnection
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object)
         (.createArrayOf connection "varchar"))))

(defmethod sql-array-type-string org.h2.jdbc.JdbcConnection
  [_]
  "ARRAY")

(defmethod to-jdbc-varchar-array org.h2.jdbc.JdbcConnection
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object))))

(defn initialize-store
  "Create initial database state"
  []
  (sql/create-table :certnames
                    ["name" "VARCHAR" "PRIMARY KEY"]
                    ["api_version" "INT" "NOT NULL"]
                    ["catalog_version" "VARCHAR" "NOT NULL"])

  (sql/create-table :tags
                    ["certname" "VARCHAR" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["name" "VARCHAR" "NOT NULL"]
                    ["PRIMARY KEY (certname, name)"])

  (sql/create-table :classes
                    ["certname" "VARCHAR" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["name" "VARCHAR" "NOT NULL"]
                    ["PRIMARY KEY (certname, name)"])

  (sql/create-table :resources
                    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
                    ["type" "VARCHAR" "NOT NULL"]
                    ["title" "VARCHAR" "NOT NULL"]
                    ["exported" "BOOLEAN" "NOT NULL"]
                    ["sourcefile" "VARCHAR"]
                    ["sourceline" "INT"])

  (sql/create-table :certname_resources
                    ["certname" "VARCHAR" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["PRIMARY KEY (certname, resource)"])

  (sql/create-table :resource_params
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["name" "VARCHAR" "NOT NULL"]
                    ["value" (sql-array-type-string "VARCHAR") "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :resource_tags
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["name" "VARCHAR" "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :edges
                    ["certname" "VARCHAR" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["source" "VARCHAR" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["target" "VARCHAR" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["type" "VARCHAR" "NOT NULL"]
                    ["PRIMARY KEY (certname, source, target, type)"])

  (sql/do-commands
   "CREATE INDEX idx_resources_type ON resources(type)")

  (sql/do-commands
   "CREATE INDEX idx_resources_params_resource ON resource_params(resource)"))


(defn persist-certname!
  "Given a certname, persist it in the db"
  [certname api-version catalog-version]
  {:pre [certname api-version catalog-version]}
  (sql/insert-record :certnames {:name certname
                                 :api_version api-version
                                 :catalog_version catalog-version}))

(defn persist-classes!
  "Given a certname and a list of classes, persist them in the db"
  [certname classes]
  {:pre [certname
         (coll? classes)]}
  (let [default-row {:certname certname}
        classes     (map #(assoc default-row :name %) classes)]
    (apply sql/insert-records :classes classes)))

(defn persist-tags!
  "Given a certname and a list of tags, persist them in the db"
  [certname tags]
  {:pre [certname
         (coll? tags)]}
  (let [default-row {:certname certname}
        tags        (map #(assoc default-row :name %) tags)]
    (apply sql/insert-records :tags tags)))

(defn resource-already-persisted?
  "Returns a boolean indicating whether or not the given resource exists in the db"
  [hash]
  {:pre [hash]}
  (sql/with-query-results result-set
    ["SELECT EXISTS(SELECT 1 FROM resources WHERE hash=?) as present" hash]
    (let [row (first result-set)]
      (row :present))))

(defn compute-hash
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
      (str)
      (digest/sha-1)))

(defn persist-resource!
  "Given a certname and a single resource, persist that resource and its parameters"
  [certname {:keys [type title exported parameters tags file line] :as resource}]
  {:pre [(every? string? #{type title})]}

  (let [hash       (compute-hash resource)
        persisted? (resource-already-persisted? hash)
        connection (sql/find-connection)]

    (when-not persisted?
      ; Add to resources table
      (sql/insert-record :resources {:hash hash :type type :title title :exported exported :sourcefile file :sourceline line})

      ; Build up a list of records for insertion
      (let [records (for [[name value] parameters]
                      ; Parameter values are represented as database
                      ; arrays (even single parameter values). This is
                      ; done to handle multi-valued parameters. As you
                      ; can't have a database array that contains
                      ; multiple types of values, we convert all
                      ; parameter values to strings.
                      (let [value-array (->> value
                                            (utils/as-collection)
                                            (map str)
                                            (to-jdbc-varchar-array))]
                        {:resource hash :name name :value value-array}))]

        ; ...and insert them
        (apply sql/insert-records :resource_params records))

      ; Add rows for each of the resource's tags
      (let [records (for [tag tags] {:resource hash :name tag})]
        (apply sql/insert-records :resource_tags records)))

    ;; Insert pointer into certname => resource map
    (sql/insert-record :certname_resources {:certname certname :resource hash})))

(defn persist-edges!
  "Persist the given edges in the database

Each edge is looked up in the supplied resources map to find a
resource object that corresponds to the edge. We then use that
resource's hash for persistence purposes.

For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
then we'll lookup a resource with that key and use its hash."
  [certname edges resources]
  {:pre [certname
         (coll? edges)
         (map? resources)]}
  (let [rows  (for [{:keys [source target relationship]} edges
                    :let [source-hash (compute-hash (resources source))
                          target-hash (compute-hash (resources target))
                          type        (name relationship)]]
                {:certname certname :source source-hash :target target-hash :type type})]
    (apply sql/insert-records :edges rows)))

(defn persist-catalog!
  "Persist the supplied catalog in the database"
  [{:keys [certname api-version version resources classes edges tags] :as catalog}]
  {:pre [(every? string? #{certname})
         (number? api-version)
         (every? coll? #{classes tags edges})
         (map? resources)]}

  (sql/transaction
   (persist-certname! certname api-version version)
   (persist-classes! certname classes)
   (persist-tags! certname tags)
   (doseq [resource (vals resources)]
     (persist-resource! certname resource))
   (persist-edges! certname edges resources)))
