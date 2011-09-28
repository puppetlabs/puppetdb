(ns com.puppetlabs.cmdb.scf.storage
  (:import (com.jolbox.bonecp BoneCPDataSource BoneCPConfig)
           (java.util.concurrent TimeUnit))
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.utils :as utils]
            [clojure.java.jdbc :as sql]
            [clojure.contrib.logging :as log]
            [digest]))

;;;; REVISIT: This needs to source the configuration data from somewhere more
;;;; useful than hard-coded content inline to the code.  Especially stolen
;;;; configuration from the core.clj, duplicated again. --daniel 2011-09-14
(def ^{:doc "The query database configuration details for SCF database."
       :private true
       :dynamic true}
  *db* {:classname "org.hsqldb.jdbcDriver"
        :subprotocol "hsqldb"
        ;; use MVCC and PostgreSQL compatible syntax; see
        ;; http://hsqldb.org/doc/2.0/guide/deployment-chapt.html#N1427E for
        ;; details of what that implies.
        ;;
        ;; shutdown=true means that when the last connection is dropped, so is
        ;; the database.  That means *nothing* lasts beyond your outermost
        ;; `with-scf-connection` invocation, which is awesome for testing.
        ;;
        ;; For production this will be, y'know, externally configured.
        :subname     "mem:cmdb;shutdown=true;hsqldb.tx=mvcc;sql.syntax_pgs=true"
        :log-statements false})



;;;; SQL Storage abstraction and management.  These methods allow us to
;;;; abstract behaviour over multiple connection types.
(defn sql-current-connection-database-name
  "Return the database product name currently in use."
  []
  (.. (sql/find-connection)
      (getMetaData)
      (getDatabaseProductName)))

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

(defmulti to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  (fn [_] (sql-current-connection-database-name)))


(defmethod sql-array-type-string "PostgreSQL"
  [basetype]
  (format "%s ARRAY" basetype))

(defmethod sql-array-query-string "PostgreSQL"
  [column]
  (format "? = ANY(%s)" column))

(defmethod to-jdbc-varchar-array "PostgreSQL"
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object)
         (.createArrayOf connection "varchar"))))

(defmethod sql-array-type-string "HSQL Database Engine"
  [basetype]
  (format "%s ARRAY[%d]" basetype 65535))

(defmethod sql-array-query-string "HSQL Database Engine"
  [column]
  (format "? IN (UNNEST(%s))" column))

(defmethod to-jdbc-varchar-array "HSQL Database Engine"
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object)
         (.createArrayOf connection "varchar"))))

(defmethod sql-array-type-string "H2"
  [_]
  "ARRAY")

;; H2 has no support for query inside an array column.

(defmethod to-jdbc-varchar-array "H2"
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object))))


;;; Wrap up the functionality of populating the initial database state.
;;;
;;; In the longer term this should be replaced with some sort of migration-
;;; style database management library, or some other model that supports
;;; upgrades in the field nicely, but this will do for now.
(defn initialize-store
  "Create initial database state"
  []
  (sql/create-table :certnames
                    ["name" "TEXT" "PRIMARY KEY"]
                    ["api_version" "INT" "NOT NULL"]
                    ["catalog_version" "TEXT" "NOT NULL"])

  (sql/create-table :tags
                    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (certname, name)"])

  (sql/create-table :classes
                    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (certname, name)"])

  (sql/create-table :resources
                    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
                    ["type" "TEXT" "NOT NULL"]
                    ["title" "TEXT" "NOT NULL"]
                    ["exported" "BOOLEAN" "NOT NULL"]
                    ["sourcefile" "TEXT"]
                    ["sourceline" "INT"])

  (sql/create-table :certname_resources
                    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["PRIMARY KEY (certname, resource)"])

  (sql/create-table :resource_params
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["value" (sql-array-type-string "TEXT") "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :resource_tags
                    ["resource" "VARCHAR(40)" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["name" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :edges
                    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["source" "TEXT" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["target" "TEXT" "REFERENCES resources(hash)" "ON DELETE CASCADE"]
                    ["type" "TEXT" "NOT NULL"]
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



;;;; Database connection-pool management and connectivity.
;;;;
;;;; BoneCP is used to provide a pool of connections shared between threads.
;;;; In addition to basic connection access, it provides tracks statements
;;;; issued during a transaction and will automatically reconnect, and replay
;;;; the statements, if the connection is dropped during activity.
(def ^{:doc "The query database connection pool singleton object.
BoneCP is internally thread-safe, so no internal locking is required.

The global is initialized during servlet setup, and will be torn down
again during servlet destruction."
       :dynamic true
       :tag BoneCPDataSource}
  *bonecp* nil)

(defn- new-connection-pool-instance
  "Create a new connection pool for the SCF database, configured appropriately,
and return it."
  [{:keys [subprotocol subname username password
           partition-conn-min partition-conn-max partition-count
           stats log-statements log-slow-statements]
    :or {partition-conn-min 1
         partition-conn-max 10
         partition-count    5
         stats              true}
    :as db}]
  (let [config (doto (new BoneCPConfig)
                 (.setDefaultAutoCommit false)
                 (.setLazyInit true)
                 (.setMinConnectionsPerPartition partition-conn-min)
                 (.setMaxConnectionsPerPartition partition-conn-max)
                 (.setPartitionCount partition-count)
                 (.setStatisticsEnabled stats)
                 ;; paste the URL back together from parts.
                 (.setJdbcUrl (str "jdbc:" subprotocol ":" subname)))]
    ;; configurable without default
    (when username (.setUsername config username))
    (when password (.setPassword config password))
    (when log-statements (.setLogStatementsEnabled config log-statements))
    (when log-slow-statements
      (.setQueryExecuteTimeLimit config log-slow-statements (TimeUnit/SECONDS)))
    ;; ...aaand, create the pool.
    (BoneCPDataSource. config)))

(defn- replace-bonecp
  "Replace the current BoneCP var root instance, shutting down the
previous instance, if any.  For use with `alter-var-root'."
  [new]
  (alter-var-root (var *bonecp*) (fn [old]
                                   (when old (.close old))
                                   new)))

(defn initialize-connection-pool
  "Initialize the connection pool with a new object.  This will shut down
any existing connection pool."
  []
  (replace-bonecp (new-connection-pool-instance *db*)))

(defn shutdown-connection-pool
  "Shut down the current connection pool entirely."
  []
  (replace-bonecp nil))

(defmacro with-scf-connection
  "Run enclosed forms with an SQL connection established to the SCF
storage database.  This will transparently manage connection failure
retry and life-cycle for the connection.

The database handle is only valid for the dynamic scope of the call,
so nothing should be returned that is bound to database results."
  [& forms]
  `(sql/with-connection {:datasource *bonecp*}
     ~@forms))
