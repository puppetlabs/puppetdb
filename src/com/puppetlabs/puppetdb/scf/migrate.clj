;; ## Schema migrations
;;
;; The `migrate!` function can be used to apply all the pending migrations to
;; the database, in ascending order of schema version. Pending is defined as
;; having a schema version greater than the current version in the database.
;;
;; A migration is specified by defining a function of arity 0 and adding it to
;; the `migrations` map, along with its schema version. To apply the migration,
;; the migration function will be invoked, and the schema version and current
;; time will be recorded in the schema_migrations table.
;;
;; _TODO: consider using multimethods for migration funcs_

(ns com.puppetlabs.puppetdb.scf.migrate
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log])
  (:use [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.puppetdb.scf.storage :only [sql-array-type-string sql-current-connection-database-name]]))

(defn initialize-store
  "Create the initial database schema."
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

  (sql/create-table :catalog_resources
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["resource" "VARCHAR(40)"]
                    ["type" "TEXT" "NOT NULL"]
                    ["title" "TEXT" "NOT NULL"]
                    ["tags" (sql-array-type-string "TEXT") "NOT NULL"]
                    ["exported" "BOOLEAN" "NOT NULL"]
                    ["sourcefile" "TEXT"]
                    ["sourceline" "INT"]
                    ["PRIMARY KEY (catalog, resource)"])

  (sql/create-table :resource_params
                    ["resource" "VARCHAR(40)"]
                    ["name" "TEXT" "NOT NULL"]
                    ["value" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (resource, name)"])

  (sql/create-table :edges
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["source" "VARCHAR(40)"]
                    ["target" "VARCHAR(40)"]
                    ["type" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (catalog, source, target, type)"])

  (sql/create-table :schema_migrations
                    ["version" "INT" "NOT NULL" "PRIMARY KEY"]
                    ["time" "TIMESTAMP" "NOT NULL"])

  (sql/do-commands
   "CREATE INDEX idx_catalogs_hash ON catalogs(hash)")

  (sql/do-commands
   "CREATE INDEX idx_certname_catalogs_certname ON certname_catalogs(certname)")

  (sql/create-table :certname_facts
                    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["fact" "TEXT" "NOT NULL"]
                    ["value" "TEXT" "NOT NULL"]
                    ["PRIMARY KEY (certname, fact)"])

  (sql/do-commands
   "CREATE INDEX idx_resources_params_resource ON resource_params(resource)")

  (sql/do-commands
   "CREATE INDEX idx_resources_params_name ON resource_params(name)")

  (sql/do-commands
   "CREATE INDEX idx_certname_facts_certname ON certname_facts(certname)")

  (sql/do-commands
   "CREATE INDEX idx_certname_facts_fact ON certname_facts(fact)")

  (sql/do-commands
   "CREATE INDEX idx_catalog_resources_type ON catalog_resources(type)")

  (sql/do-commands
   "CREATE INDEX idx_catalog_resources_resource ON catalog_resources(resource)")

  (sql/do-commands
   "CREATE INDEX idx_catalog_resources_tags ON catalog_resources(tags)"))

(defn allow-node-deactivation
  "Add a column storing when a node was deactivated."
  []
  (sql/do-commands
   "ALTER TABLE certnames ADD deactivated TIMESTAMP WITH TIME ZONE"))

(defn add-catalog-timestamps
  "Add a column to the certname_catalogs table to store a timestamp."
  []
  (sql/do-commands
   "ALTER TABLE certname_catalogs ADD timestamp TIMESTAMP WITH TIME ZONE"))

(defn add-certname-facts-metadata-table
  "Add a certname_facts_metadata table to aggregate certname_facts entries and
  store metadata (eg. timestamps)."
  []
  (sql/create-table :certname_facts_metadata
                    ["certname" "TEXT" "UNIQUE" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
                    ["timestamp" "TIMESTAMP WITH TIME ZONE"]
                    ["PRIMARY KEY (certname, timestamp)"])
  (sql/do-prepared
   "INSERT INTO certname_facts_metadata (certname,timestamp) SELECT name, ? FROM certnames"
   [(to-timestamp (now))])

  ;; First we get rid of the existing foreign key to certnames
  (let [[result & _] (query-to-vec
                      (str "SELECT constraint_name FROM information_schema.table_constraints "
                           "WHERE LOWER(table_name) = 'certname_facts' AND LOWER(constraint_type) = 'foreign key'"))
        constraint   (:constraint_name result)]
    (sql/do-commands
     (str "ALTER TABLE certname_facts DROP CONSTRAINT " constraint)))

  ;; Then we replace it with a foreign key to certname_facts_metadata
  (sql/do-commands
   (str "ALTER TABLE certname_facts "
        "ADD FOREIGN KEY (certname) REFERENCES certname_facts_metadata(certname) ON DELETE CASCADE")))

(defn add-missing-indexes
  "Add several new indexes:
    * catalog_resources USING (catalog)
    * catalog_resources USING (type,title)
    * catalog_resources USING gin(tags) [only when using postgres]"
  []
  (sql/do-commands
    "CREATE INDEX idx_catalog_resources_catalog ON catalog_resources(catalog)"
    "CREATE INDEX idx_catalog_resources_type_title ON catalog_resources(type,title)")

  (when (= (sql-current-connection-database-name) "PostgreSQL")
    (sql/do-commands
      "CREATE INDEX idx_catalog_resources_tags_gin ON catalog_resources USING gin(tags)")))

(defn allow-historical-catalogs
  "This relaxes the uniqueness constraints on `certname_catalogs`, allowing a
  single node to have multiple catalogs. The new primary key for
  `certname_catalogs` is (certname,catalog,timestamp) with an additional index
  on (certname,catalog)."
  []
  ;; Find the existing primary key and remove it
  (let [[result & _] (query-to-vec
                       (str "SELECT constraint_name FROM information_schema.table_constraints "
                            "WHERE LOWER(table_name) = 'certname_catalogs' AND LOWER(constraint_type) = 'primary key'"))
        constraint   (:constraint_name result)]
    (sql/do-commands
      (str "ALTER TABLE certname_catalogs DROP CONSTRAINT " constraint)))

  ;; Also remove those darn uniqueness constraints
  (let [results (query-to-vec
                  (str "SELECT constraint_name FROM information_schema.table_constraints "
                       "WHERE LOWER(table_name) = 'certname_catalogs' AND LOWER(CONSTRAINT_TYPE) = 'unique';"))
        constraints (map :constraint_name results)]
    (doseq [constraint constraints]
      (sql/do-commands
        (str "ALTER TABLE certname_catalogs DROP CONSTRAINT " constraint))))

  (sql/do-commands
    (str "ALTER TABLE certname_catalogs ADD PRIMARY KEY (certname,catalog,timestamp)")
    (str "CREATE INDEX idx_certname_catalogs_certname_catalog ON certname_catalogs(certname,catalog)")))

;; TODO: Migrate the data, too.
(defn dedup-resource-metadata
  []
  (sql/do-commands
    "ALTER TABLE catalog_resources RENAME TO resource_metadata "
    "ALTER TABLE resource_metadata ADD hash VARCHAR(40) NOT NULL UNIQUE"
    "ALTER TABLE resource_metadata DROP COLUMN catalog"
    "ALTER TABLE resource_metadata DROP COLUMN tags")

  (sql/create-table :catalog_resources
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["resource_metadata" "VARCHAR(40)" "REFERENCES resource_metadata(hash)" "ON DELETE CASCADE"]
                    ["PRIMARY KEY (catalog, resource_metadata)"])

  (sql/create-table :resource_tags
                    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
                    ["resource_metadata" "VARCHAR(40)" "REFERENCES resource_metadata(hash)" "ON DELETE CASCADE"]
                    ["tags" (sql-array-type-string "TEXT") "NOT NULL"]
                    ["PRIMARY KEY (catalog, resource_metadata)"])

  (when (= (sql-current-connection-database-name) "PostgreSQL") (sql/do-commands
    "CREATE INDEX idx_resource_tags_tags_gin ON resource_tags USING gin(tags)")))

;; The available migrations, as a map from migration version to migration
;; function.
(def migrations
  {1 initialize-store
   2 allow-node-deactivation
   3 add-catalog-timestamps
   4 add-certname-facts-metadata-table
   5 add-missing-indexes
   6 allow-historical-catalogs
   7 dedup-resource-metadata})

(defn schema-version
  "Returns the current version of the schema, or 0 if the schema
version can't be determined."
  []
  (try
    (let [query   "SELECT version FROM schema_migrations ORDER BY version DESC LIMIT 1"
          results (sql/transaction
                   (query-to-vec query))]
      (:version (first results)))
    (catch java.sql.SQLException e
      0)))

(defn- record-migration!
  "Records a migration by storing its version in the schema_migrations table,
along with the time at which the migration was performed."
  [version]
  {:pre [(integer? version)]}
  (sql/do-prepared
   "INSERT INTO schema_migrations (version, time) VALUES (?, ?)"
   [version (to-timestamp (now))]))

(defn pending-migrations
  "Returns a collection of pending migrations, ordered from oldest to latest."
  []
  {:post [(map? %)
          (sorted? %)
          (apply < 0 (keys %))
          (<= (count %) (count migrations))]}
  (let [current-version (schema-version)]
    (into (sorted-map)
          (filter #(> (key %) current-version) migrations))))

(defn migrate!
  "Migrates database to the latest schema version. Does nothing if database is
already at the latest schema version."
  []
  (if-let [pending (seq (pending-migrations))]
    (sql/transaction
     (doseq [[version migration] pending]
       (log/info (format "Migrating to version %d" version))
       (migration)
       (record-migration! version)))
    (log/info "There are no pending migrations")))
