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
;; NOTE: in order to support bug-fix schema changes to older branches without
;; breaking the ability to upgrade, it is possible to define a sequence of
;; migrations with non-sequential integers.  e.g., if the 1.0.x branch
;; contains migrations 1-5, and the 2.0.x branch contains schema migrations
;; 1-10, and then a bugfix schema change (such as creating or adding an index)
;; is identified, this migration can be defined as #11 in both branches.  Code
;; in the 1.0.x branch should happily apply #11 even though it does not have
;; a definition for 6-10.  Then when a 1.0.x user upgrades to 2.0.x, migrations
;; 6-10 will be applied, and 11 will be skipped because it's already been run.
;; Because of this, it is crucial to be extremely careful about numbering new
;; migrations if they are going into multiple branches.  It's also crucial to
;; be absolutely certain that the schema change in question is compatible
;; with both branches and that the migrations missing from the earlier branch
;; can reasonably and safely be applied *after* the bugfix migration, because
;; that is what will happen for upgrading users.
;;
;; In short, here are some guidelines re: applying schema changes to multiple
;; branches:
;;
;; 1. If at all possible, avoid it.
;; 2. Seriously, are you sure you need to do this? :)
;; 3. OK, if you really must do it, make sure that the schema change in question
;;    is as independent as humanly possible.  For example, things like creating
;;    or dropping an index on a table should be fairly self-contained.  You should
;;    think long and hard about any change more complex than that.
;; 4. Determine what the latest version of the schema is in each of the two branches.
;; 5. Examine every migration that exists in the newer branch but not the older
;;    branch, and make sure that your new schema change will not conflict with
;;    *any* of those migrations.  Your change must be able to execute successfully
;;    regardless of whether it is applied BEFORE all of those migrations or AFTER
;;    them.
;; 6. If you're certain you've met the conditions described above, choose the next
;;    available integer from the *newer* branch and add your migration to both
;;    branches using this integer.  This will result in a gap between the integers
;;    in the migrations array in the old branch, but that is not a problem.
;;
;; _TODO: consider using multimethods for migration funcs_

(ns com.puppetlabs.puppetdb.scf.migrate
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [com.puppetlabs.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink])
  (:use [clojure.set]
        [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only [query-to-vec with-query-results-cursor]]
        [com.puppetlabs.puppetdb.scf.storage-utils :only [sql-array-type-string
                                                          sql-current-connection-database-name
                                                          sql-current-connection-database-version
                                                          postgres?
                                                          pg-newer-than-8-1?]]))

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

  (when (postgres?)
    (if (pg-newer-than-8-1?)
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
    (if (postgres?)
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

(defn add-parameter-cache
  "Creates the new resource_params_cache table, and populates it using
  the existing parameters in the database."
  []
  ;; Create cache table
  (sql/create-table :resource_params_cache
                    ["resource" "VARCHAR(40)"]
                    ["parameters" "TEXT"]
                    ["PRIMARY KEY (resource)"])

  (log/warn "Building resource parameters cache. This make take a few minutes, but faster resource queries are worth it.")

  ;; Loop over all parameters, and insert a cache entry for each resource
  (let [query    "SELECT resource, name, value from resource_params ORDER BY resource"
        collapse (fn [rows]
                   (let [resource (:resource (first rows))
                         params   (into {} (map #(vector (:name %) (json/parse-string (:value %))) rows))]
                     [resource params]))]

    (with-query-results-cursor query [] rs
      (let [param-sets (->> rs
                            (partition-by :resource)
                            (map collapse))]
        (doseq [[resource params] param-sets]
          (sql/insert-record :resource_params_cache {:resource   resource
                                                     :parameters (json/generate-string params)})))))

  ;; Create NULL entries for resources that have no parameters
  (sql/do-commands
   "INSERT INTO resource_params_cache
    SELECT DISTINCT resource, NULL FROM catalog_resources WHERE NOT EXISTS
    (SELECT 1 FROM resource_params WHERE resource=catalog_resources.resource)")

  (sql/do-commands
   "ALTER TABLE catalog_resources ADD FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE")

  (sql/do-commands
   "ALTER TABLE resource_params ADD FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE"))

(defn add-event-status-index
  "Add an index to the `status` column of the event table."
  []
  (sql/do-commands
    "CREATE INDEX idx_resource_events_status ON resource_events(status)"))

(defn increase-puppet-version-field-length
  "Increase the length of the puppet_version field in the reports table, as we've
  encountered some version strings that are longer than 40 chars."
  []
  (sql/do-commands
    (condp = (sql-current-connection-database-name)
      "PostgreSQL" "ALTER TABLE reports ALTER puppet_version TYPE VARCHAR(255)"
      "HSQL Database Engine" "ALTER TABLE reports ALTER puppet_version VARCHAR(255)"
      (throw (IllegalArgumentException.
               (format "Unsupported database engine '%s'"
                 (sql-current-connection-database-name)))))))

(defn burgundy-schema-changes
  "Schema changes for the initial release of Burgundy. These include:

    - Add 'file' and 'line' columns to the event table
    - A column for the resource's containment path in the resource_events table
    - A column for the transaction uuid in the reports & catalogs tables
    - Renames the `sourcefile` and `sourceline` columns on the `catalog_resources`
      table to `file` and `line` for consistency.
    - Add index to 'property' column in resource_events table"
  []
  (sql/do-commands
    "ALTER TABLE resource_events ADD COLUMN file VARCHAR(1024) DEFAULT NULL"
    "ALTER TABLE resource_events ADD COLUMN line INTEGER DEFAULT NULL")
  (sql/do-commands
    (format "ALTER TABLE resource_events ADD containment_path %s" (sql-array-type-string "TEXT"))
    "ALTER TABLE resource_events ADD containing_class VARCHAR(255)"
    "CREATE INDEX idx_resource_events_containing_class ON resource_events(containing_class)"
    "CREATE INDEX idx_resource_events_property ON resource_events(property)")
  (sql/do-commands
    ;; It would be nice to change the transaction UUID column to NOT NULL in the future
    ;; once we stop supporting older versions of Puppet that don't have this field.
    "ALTER TABLE reports ADD COLUMN transaction_uuid VARCHAR(255) DEFAULT NULL"
    "CREATE INDEX idx_reports_transaction_uuid ON reports(transaction_uuid)"
    "ALTER TABLE catalogs ADD COLUMN transaction_uuid VARCHAR(255) DEFAULT NULL"
    "CREATE INDEX idx_catalogs_transaction_uuid ON catalogs(transaction_uuid)")
  (sql/do-commands
    (if (postgres?)
      "ALTER TABLE catalog_resources RENAME COLUMN sourcefile TO file"
      "ALTER TABLE catalog_resources ALTER COLUMN sourcefile RENAME TO file")
    (if (postgres?)
      "ALTER TABLE catalog_resources RENAME COLUMN sourceline TO line"
      "ALTER TABLE catalog_resources ALTER COLUMN sourceline RENAME TO line")))

(defn add-latest-reports-table
  "Add `latest_reports` table for easy lookup of latest report for each certname."
  []
  (sql/create-table :latest_reports
    ["certname" "TEXT" "NOT NULL" "PRIMARY KEY" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
    ["report" "VARCHAR(40)" "NOT NULL" "REFERENCES reports(hash)" "ON DELETE CASCADE"])
  (sql/do-commands
    "CREATE INDEX idx_latest_reports_report ON latest_reports(report)")
  (sql/do-commands
    "INSERT INTO latest_reports (certname, report)
        SELECT reports.certname, reports.hash
        FROM reports INNER JOIN (
          SELECT reports.certname, MAX(reports.end_time) as max_end_time
             FROM reports
             GROUP BY reports.certname
        ) latest
          ON reports.certname = latest.certname
          AND reports.end_time = latest.max_end_time"))

(defn drop-duplicate-indexes
  "Remove indexes that are duplicated by primary keys or other
  constraints"
  []
  (sql/do-commands "DROP INDEX idx_catalogs_hash")
  (sql/do-commands "DROP INDEX idx_certname_catalogs_certname"))

(defn drop-resource-tags-index
  "Remove the resource tags index, it can get very large and is not used"
  []
  (when (pg-newer-than-8-1?)
    (sql/do-commands "DROP INDEX IF EXISTS idx_catalog_resources_tags_gin")))

(defn use-bigint-instead-of-catalog-hash
  "This migration converts all catalog hash instances to use bigint sequences instead"
  []
  (sql/do-commands
    ;; catalogs: Create new table without constraints
    "CREATE TABLE catalogs_transform (
      id bigserial NOT NULL,
      hash character varying(40) NOT NULL,
      api_version integer NOT NULL,
      catalog_version text NOT NULL,
      transaction_uuid character varying(255) DEFAULT NULL)"

    ;; catalogs: Insert data from old table
    "INSERT INTO catalogs_transform (hash, api_version, catalog_version, transaction_uuid)
      SELECT hash, api_version, catalog_version, transaction_uuid
        FROM catalogs"

    ;; certname_catalogs: Create new table without constraints
    "CREATE TABLE certname_catalogs_transform (
      catalog_id bigint NOT NULL,
      certname text NOT NULL,
      timestamp TIMESTAMP WITH TIME ZONE)"

    ;; certname_catalogs: insert data from old table
    "INSERT INTO certname_catalogs_transform (catalog_id, certname, timestamp)
      SELECT c.id, certname, timestamp
        FROM certname_catalogs cc, catalogs_transform c
        WHERE cc.catalog = c.hash"

    ;; edges: create new table
    "CREATE TABLE edges_transform (
      catalog_id bigint NOT NULL,
      source character varying(40) NOT NULL,
      target character varying(40) NOT NULL,
      type text NOT NULL)"

    ;; edges: insert data from old table
    "INSERT INTO edges_transform (catalog_id, source, target, type)
      SELECT c.id, source, target, type
        FROM edges e, catalogs_transform c
        WHERE e.catalog = c.hash"

    ;; catalog_resources: create new table
    (str "CREATE TABLE catalog_resources_transform (
      catalog_id bigint NOT NULL,
      resource character varying(40) NOT NULL,
      type text NOT NULL,
      title text NOT NULL,
      tags " (sql-array-type-string "TEXT") " NOT NULL,
      exported boolean NOT NULL,
      file text,
      line integer)")

    ;; catalog_resources: insert data from old table
    "INSERT INTO catalog_resources_transform (catalog_id, resource, type, title, tags, exported, file, line)
      SELECT c.id, resource, type, title, tags, exported, file, line
        FROM catalog_resources cr, catalogs_transform c
        WHERE cr.catalog = c.hash"

    ;; Drop the old tables
    "DROP TABLE catalog_resources"
    "DROP TABLE certname_catalogs"
    "DROP TABLE edges"
    "DROP TABLE catalogs"

    ;; Rename the new tables
    "ALTER TABLE catalog_resources_transform RENAME to catalog_resources"
    "ALTER TABLE certname_catalogs_transform RENAME to certname_catalogs"
    "ALTER TABLE edges_transform RENAME to edges"
    "ALTER TABLE catalogs_transform RENAME to catalogs"

    ;; catalogs: Add constraints to new catalogs table
    ;;   hsqldb automatically creates the primary key when we created the table
    ;;   with a bigserial so its only needed for pgsql.
    (if (postgres?)
      "ALTER TABLE catalogs
        ADD CONSTRAINT catalogs_pkey PRIMARY KEY (id)"
      "select 1")
    "ALTER TABLE catalogs
      ADD CONSTRAINT catalogs_hash_key UNIQUE (hash)"

    ;; catalogs: create other indexes
    "CREATE INDEX idx_catalogs_transaction_uuid
      ON catalogs (transaction_uuid)"

    ;; certname_catalogs: Add constraints
    "ALTER TABLE certname_catalogs
      ADD CONSTRAINT certname_catalogs_pkey PRIMARY KEY (certname, catalog_id)"
    "ALTER TABLE certname_catalogs
      ADD CONSTRAINT certname_catalogs_catalog_id_fkey FOREIGN KEY (catalog_id)
          REFERENCES catalogs (id)
          ON UPDATE NO ACTION ON DELETE CASCADE"
    "ALTER TABLE certname_catalogs
      ADD CONSTRAINT certname_catalogs_certname_fkey FOREIGN KEY (certname)
          REFERENCES certnames (name)
          ON UPDATE NO ACTION ON DELETE CASCADE"
    "ALTER TABLE certname_catalogs
      ADD CONSTRAINT certname_catalogs_certname_key UNIQUE (certname)"

    ;; edges: add constraints
    "ALTER TABLE edges
      ADD CONSTRAINT edges_pkey PRIMARY KEY (catalog_id, source, target, type)"
    "ALTER TABLE edges
      ADD CONSTRAINT edges_catalog_id_fkey FOREIGN KEY (catalog_id)
          REFERENCES catalogs (id)
          ON UPDATE NO ACTION ON DELETE CASCADE"

    ;; catalog_resources: add constraints
    "ALTER TABLE catalog_resources
      ADD CONSTRAINT catalog_resources_pkey PRIMARY KEY (catalog_id, resource)"
    "ALTER TABLE catalog_resources
      ADD CONSTRAINT catalog_resources_catalog_id_fkey FOREIGN KEY (catalog_id)
          REFERENCES catalogs (id)
          ON UPDATE NO ACTION ON DELETE CASCADE"
    "ALTER TABLE catalog_resources
      ADD CONSTRAINT catalog_resources_resource_fkey FOREIGN KEY (resource)
          REFERENCES resource_params_cache (resource)
          ON UPDATE NO ACTION ON DELETE CASCADE"

    ;; catalog_resources: create other indexes
    "CREATE INDEX idx_catalog_resources_resource
      ON catalog_resources (resource)"

    "CREATE INDEX idx_catalog_resources_type
      ON catalog_resources (type)"

    "CREATE INDEX idx_catalog_resources_type_title
      ON catalog_resources (type)"))

(defn add-index-on-exported-column
  "This migration adds an index to catalog_resources.exported. It will
  optionally create a partial index on PostgreSQL to reduce disk space, and
  since the more common value is false its not useful to index this."
  []
  (sql/do-commands
    (if (postgres?)
      "CREATE INDEX idx_catalog_resources_exported_true
         ON catalog_resources (exported) WHERE exported = true"
      "CREATE INDEX idx_catalog_resources_exported
         ON catalog_resources (exported)")))

;; The available migrations, as a map from migration version to migration function.
(def migrations
  {1 initialize-store
   2 allow-node-deactivation
   3 add-catalog-timestamps
   4 add-certname-facts-metadata-table
   5 add-missing-indexes
   6 drop-old-tags-index
   7 drop-classes-and-tags
   8 rename-fact-column
   9 add-reports-tables
   10 add-event-status-index
   11 increase-puppet-version-field-length
   12 burgundy-schema-changes
   13 add-latest-reports-table
   14 add-parameter-cache
   15 drop-duplicate-indexes
   16 drop-resource-tags-index
   17 use-bigint-instead-of-catalog-hash
   18 add-index-on-exported-column})

(def desired-schema-version (apply max (keys migrations)))

(defn record-migration!
  "Records a migration by storing its version in the schema_migrations table,
  along with the time at which the migration was performed."
  [version]
  {:pre [(integer? version)]}
  (sql/do-prepared
   "INSERT INTO schema_migrations (version, time) VALUES (?, ?)"
   [version (to-timestamp (now))]))

(defn applied-migrations
  "Returns a collection of migrations that have been run against this database
  already, ordered from oldest to latest."
  []
  {:post  [(sorted? %)
           (set? %)
           (apply < 0 %)]}
  (try
    (let [query   "SELECT version FROM schema_migrations ORDER BY version"
          results (sql/transaction (query-to-vec query))]
      (apply sorted-set (map :version results)))
    (catch java.sql.SQLException e
      (sorted-set))))

(defn pending-migrations
  "Returns a collection of pending migrations, ordered from oldest to latest."
  []
  {:post [(map? %)
          (sorted? %)
          (apply < 0 (keys %))
          (<= (count %) (count migrations))]}
  (let [pending (difference (kitchensink/keyset migrations) (applied-migrations))]
      (into (sorted-map)
        (select-keys migrations pending))))

(defn migrate!
  "Migrates database to the latest schema version. Does nothing if database is
  already at the latest schema version."
  []
  (if-let [unexpected (first (difference (applied-migrations) (kitchensink/keyset migrations)))]
    (throw (IllegalStateException.
              (format "Your PuppetDB database contains a schema migration numbered %d, but this version of PuppetDB does not recognize that version."
                    unexpected))))

  (if-let [pending (seq (pending-migrations))]
    (sql/transaction
     (doseq [[version migration] pending]
       (log/info (format "Applying migration version %d" version))
       (migration)
       (record-migration! version)))
    (log/info "There are no pending migrations")))
