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
            [clojure.tools.logging :as log]
            [clojure.string :as string])
  (:use [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.puppetdb.scf.storage :only [sql-array-type-string
                                                    sql-current-connection-database-name
                                                    sql-current-connection-database-version]]))

(defn- drop-constraints
  "Drop all constraints of given `constraint-type` on `table`."
  [table constraint-type]
  (let [results     (query-to-vec
                      (str "SELECT constraint_name FROM information_schema.table_constraints "
                           "WHERE LOWER(table_name) = LOWER(?) AND LOWER(constraint_type) = LOWER(?)")
                      table constraint-type)
        constraints (map :constraint_name results)]
    (if (seq constraints)
      (apply sql/do-commands
             (for [constraint constraints]
               (format "ALTER TABLE %s DROP CONSTRAINT %s" table constraint)))
      (throw (IllegalArgumentException. (format "No %s constraint exists on the table '%s'" constraint-type table))))))

(defn- drop-primary-key
  "Drop the primary key on the given `table`."
  [table]
  (drop-constraints table "primary key"))

(defn- drop-foreign-keys
  "Drop all foreign keys on the given `table`. Does not currently support
  selecting a single key to drop."
  [table]
  (drop-constraints table "foreign key"))

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
  (drop-foreign-keys "certname_facts")

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
  (log/warn "Adding additional indexes; this may take several minutes, depending on the size of your database. Trust us, it will all be worth it in the end.")
  (sql/do-commands
    "CREATE INDEX idx_catalog_resources_catalog ON catalog_resources(catalog)"
    "CREATE INDEX idx_catalog_resources_type_title ON catalog_resources(type,title)")

  (when (= (sql-current-connection-database-name) "PostgreSQL")
    (if (pos? (compare (sql-current-connection-database-version) [8 1]))
      (sql/do-commands
        "CREATE INDEX idx_catalog_resources_tags_gin ON catalog_resources USING gin(tags)")
      (log/warn (format "Version %s of PostgreSQL is too old to support fast tag searches; skipping GIN index on tags. For reliability and performance reasons, consider upgrading to the latest stable version." (string/join "." (sql-current-connection-database-version)))))))

(defn drop-old-tags-index
  "Drops the non-GIN tags index, which is never used (because nobody
  does equality comparisons against array columns)"
  []
  (sql/do-commands
    "DROP INDEX idx_catalog_resources_tags"))

(defn drop-classes-and-tags
  "Removes the `classes` and `tags` tables, as this information can be derived
  from resources."
  []
  (sql/drop-table :classes)
  (sql/drop-table :tags))

(defn rename-fact-column
  "Renames the `fact` column on `certname_facts` to `name`, for consistency."
  []
  (sql/do-commands
    (if (= (sql-current-connection-database-name) "PostgreSQL")
      "ALTER TABLE certname_facts RENAME COLUMN fact TO name"
      "ALTER TABLE certname_facts ALTER COLUMN fact RENAME TO name")
    "ALTER INDEX idx_certname_facts_fact RENAME TO idx_certname_facts_name"))

(defn add-reports-tables
  "Add a resource_events and reports tables."
  []
  (sql/create-table :reports
    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
    ["puppet_version" "VARCHAR(40)" "NOT NULL"]
    ["report_format" "SMALLINT" "NOT NULL"]
    ["configuration_version" "VARCHAR(255)" "NOT NULL"]
    ["start_time" "TIMESTAMP WITH TIME ZONE" "NOT NULL"]
    ["end_time" "TIMESTAMP WITH TIME ZONE" "NOT NULL"]
    ["receive_time" "TIMESTAMP WITH TIME ZONE" "NOT NULL"])

  (sql/create-table :resource_events
    ["report" "VARCHAR(40)" "NOT NULL" "REFERENCES reports(hash)" "ON DELETE CASCADE"]
    ["status" "VARCHAR(40)" "NOT NULL"]
    ["timestamp" "TIMESTAMP WITH TIME ZONE" "NOT NULL"]
    ["resource_type" "TEXT" "NOT NULL"]
    ["resource_title" "TEXT" "NOT NULL"]
    ;; I wish these next two could be "NOT NULL", but for now we are
    ;; fabricating skipped resources as events, and in those cases we don't
    ;; have any legitimate values to put into these fields.
    ["property" "VARCHAR(40)"]
    ["new_value" "TEXT"]
    ["old_value" "TEXT"]
    ["message" "TEXT"]
    ; we can't set the "correct" primary key because `property` is nullable
    ; (because of skipped resources).
    ; We decided to just use a UNIQUE constraint for now, but another option
    ; would be to split this out into two tables.
    ["CONSTRAINT constraint_resource_events_unique UNIQUE (report, resource_type, resource_title, property)"])

  (sql/do-commands
    "CREATE INDEX idx_reports_certname ON reports(certname)")

  ; I presume we'll be doing a decent number of queries sorted by a timestamp,
  ; and this seems like the most likely candidate out of the timestamp fields
  (sql/do-commands
    "CREATE INDEX idx_reports_end_time ON reports(end_time)")

  (sql/do-commands
    "CREATE INDEX idx_resource_events_report ON resource_events(report)")

  (sql/do-commands
    "CREATE INDEX idx_resource_events_resource_type ON resource_events(resource_type)")

  (sql/do-commands
    "CREATE INDEX idx_resource_events_resource_type_title ON resource_events(resource_type, resource_title)")

  (sql/do-commands
    "CREATE INDEX idx_resource_events_timestamp ON resource_events(timestamp)"))


;; The available migrations, as a map from migration version to migration
;; function.
(def migrations
  {1 initialize-store
   2 allow-node-deactivation
   3 add-catalog-timestamps
   4 add-certname-facts-metadata-table
   5 add-missing-indexes
   6 drop-old-tags-index
   7 drop-classes-and-tags
   8 rename-fact-column
   9 add-reports-tables})

(def desired-schema-version (apply max (keys migrations)))

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
          (filter #(> (key %) current-version) migrations)) ))

(defn migrate!
  "Migrates database to the latest schema version. Does nothing if database is
already at the latest schema version."
  []
  (let [current-schema-version (schema-version)]
    (when (< desired-schema-version current-schema-version)
      (throw (IllegalStateException.
              (format "This version of PuppetDB works with schema version %d, but the DB is at version %d"
                      desired-schema-version current-schema-version)))))

  (if-let [pending (seq (pending-migrations))]
    (sql/transaction
     (doseq [[version migration] pending]
       (log/info (format "Migrating to version %d" version))
       (migration)
       (record-migration! version)))
    (log/info "There are no pending migrations")))
