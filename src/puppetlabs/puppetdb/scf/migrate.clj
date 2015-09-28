(ns puppetlabs.puppetdb.scf.migrate
  "Schema migrations

   The `migrate!` function can be used to apply all the pending migrations to
   the database, in ascending order of schema version. Pending is defined as
   having a schema version greater than the current version in the database.

   A migration is specified by defining a function of arity 0 and adding it to
   the `migrations` map, along with its schema version. To apply the migration,
   the migration function will be invoked, and the schema version and current
   time will be recorded in the schema_migrations table.

   NOTE: in order to support bug-fix schema changes to older branches without
   breaking the ability to upgrade, it is possible to define a sequence of
   migrations with non-sequential integers.  e.g., if the 1.0.x branch
   contains migrations 1-5, and the 2.0.x branch contains schema migrations
   1-10, and then a bugfix schema change (such as creating or adding an index)
   is identified, this migration can be defined as #11 in both branches.  Code
   in the 1.0.x branch should happily apply #11 even though it does not have
   a definition for 6-10.  Then when a 1.0.x user upgrades to 2.0.x, migrations
   6-10 will be applied, and 11 will be skipped because it's already been run.
   Because of this, it is crucial to be extremely careful about numbering new
   migrations if they are going into multiple branches.  It's also crucial to
   be absolutely certain that the schema change in question is compatible
   with both branches and that the migrations missing from the earlier branch
   can reasonably and safely be applied *after* the bugfix migration, because
   that is what will happen for upgrading users.

   In short, here are some guidelines re: applying schema changes to multiple
   branches:

   1. If at all possible, avoid it.
   2. Seriously, are you sure you need to do this? :)
   3. OK, if you really must do it, make sure that the schema change in question
      is as independent as humanly possible.  For example, things like creating
      or dropping an index on a table should be fairly self-contained.  You should
      think long and hard about any change more complex than that.
   4. Determine what the latest version of the schema is in each of the two branches.
   5. Examine every migration that exists in the newer branch but not the older
      branch, and make sure that your new schema change will not conflict with
      *any* of those migrations.  Your change must be able to execute successfully
      regardless of whether it is applied BEFORE all of those migrations or AFTER
      them.
   6. If you're certain you've met the conditions described above, choose the next
      available integer from the *newer* branch and add your migration to both
      branches using this integer.  This will result in a gap between the integers
      in the migrations array in the old branch, but that is not a problem.

   _TODO: consider using multimethods for migration funcs_"
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [puppetlabs.puppetdb.scf.migration-legacy :as legacy]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.config :as conf]))

(defn- drop-constraints
  "Drop all constraints of given `constraint-type` on `table`."
  [table constraint-type]
  (let [results     (jdbc/query-to-vec
                     (str "SELECT constraint_name FROM information_schema.table_constraints "
                          "WHERE LOWER(table_name) = LOWER(?) AND LOWER(constraint_type) = LOWER(?)")
                     table constraint-type)
        constraints (map :constraint_name results)]
    (if (seq constraints)
      (apply jdbc/do-commands
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
  (jdbc/do-commands
   (sql/create-table-ddl
    :certnames
    ["name" "TEXT" "PRIMARY KEY"])

   (sql/create-table-ddl
    :catalogs
    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
    ["api_version" "INT" "NOT NULL"]
    ["catalog_version" "TEXT" "NOT NULL"])

   (sql/create-table-ddl
    :certname_catalogs
    ["certname" "TEXT" "UNIQUE" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
    ["catalog" "VARCHAR(40)" "UNIQUE" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
    ["PRIMARY KEY (certname, catalog)"])

   (sql/create-table-ddl
    :tags
    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
    ["name" "TEXT" "NOT NULL"]
    ["PRIMARY KEY (catalog, name)"])

   (sql/create-table-ddl
    :classes
    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
    ["name" "TEXT" "NOT NULL"]
    ["PRIMARY KEY (catalog, name)"])

   (sql/create-table-ddl
    :catalog_resources
    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
    ["resource" "VARCHAR(40)"]
    ["type" "TEXT" "NOT NULL"]
    ["title" "TEXT" "NOT NULL"]
    ["tags" (sutils/sql-array-type-string "TEXT") "NOT NULL"]
    ["exported" "BOOLEAN" "NOT NULL"]
    ["sourcefile" "TEXT"]
    ["sourceline" "INT"]
    ["PRIMARY KEY (catalog, resource)"])

   (sql/create-table-ddl
    :resource_params
    ["resource" "VARCHAR(40)"]
    ["name" "TEXT" "NOT NULL"]
    ["value" "TEXT" "NOT NULL"]
    ["PRIMARY KEY (resource, name)"])

   (sql/create-table-ddl
    :edges
    ["catalog" "VARCHAR(40)" "REFERENCES catalogs(hash)" "ON DELETE CASCADE"]
    ["source" "VARCHAR(40)"]
    ["target" "VARCHAR(40)"]
    ["type" "TEXT" "NOT NULL"]
    ["PRIMARY KEY (catalog, source, target, type)"])

   (sql/create-table-ddl
    :schema_migrations
    ["version" "INT" "NOT NULL" "PRIMARY KEY"]
    ["time" "TIMESTAMP" "NOT NULL"])

   "CREATE INDEX idx_catalogs_hash ON catalogs(hash)"
   "CREATE INDEX idx_certname_catalogs_certname ON certname_catalogs(certname)"

   (sql/create-table-ddl
    :certname_facts
    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
    ["fact" "TEXT" "NOT NULL"]
    ["value" "TEXT" "NOT NULL"]
    ["PRIMARY KEY (certname, fact)"])

   "CREATE INDEX idx_resources_params_resource ON resource_params(resource)"
   "CREATE INDEX idx_resources_params_name ON resource_params(name)"
   "CREATE INDEX idx_certname_facts_certname ON certname_facts(certname)"
   "CREATE INDEX idx_certname_facts_fact ON certname_facts(fact)"
   "CREATE INDEX idx_catalog_resources_type ON catalog_resources(type)"
   "CREATE INDEX idx_catalog_resources_resource ON catalog_resources(resource)"
   "CREATE INDEX idx_catalog_resources_tags ON catalog_resources(tags)"))

(defn allow-node-deactivation
  "Add a column storing when a node was deactivated."
  []
  (jdbc/do-commands
   "ALTER TABLE certnames ADD deactivated TIMESTAMP WITH TIME ZONE"))

(defn add-catalog-timestamps
  "Add a column to the certname_catalogs table to store a timestamp."
  []
  (jdbc/do-commands
   "ALTER TABLE certname_catalogs ADD timestamp TIMESTAMP WITH TIME ZONE"))

(defn add-certname-facts-metadata-table
  "Add a certname_facts_metadata table to aggregate certname_facts entries and
  store metadata (eg. timestamps)."
  []
  (jdbc/do-commands
   (sql/create-table-ddl
    :certname_facts_metadata
    ["certname" "TEXT" "UNIQUE" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
    ["timestamp" "TIMESTAMP WITH TIME ZONE"]
    ["PRIMARY KEY (certname, timestamp)"]))
  (jdbc/do-prepared "INSERT INTO certname_facts_metadata (certname,timestamp)
                       SELECT name, ? FROM certnames"
                    [(to-timestamp (now))])

  ;; First we get rid of the existing foreign key to certnames
  (drop-foreign-keys "certname_facts")

  ;; Then we replace it with a foreign key to certname_facts_metadata
  (jdbc/do-commands
   (str "ALTER TABLE certname_facts "
        "ADD FOREIGN KEY (certname) REFERENCES certname_facts_metadata(certname) ON DELETE CASCADE")))

(defn add-missing-indexes
  "Add several new indexes:
    * catalog_resources USING (catalog)
    * catalog_resources USING (type,title)
    * catalog_resources USING gin(tags) [only when using postgres]"
  []
  (log/warn "Adding additional indexes; this may take several minutes, depending on the size of your database. Trust us, it will all be worth it in the end.")
  (jdbc/do-commands
   "CREATE INDEX idx_catalog_resources_catalog ON catalog_resources(catalog)"
   "CREATE INDEX idx_catalog_resources_type_title ON catalog_resources(type,title)"
   (if (sutils/postgres?)
     "CREATE INDEX idx_catalog_resources_tags_gin ON catalog_resources USING gin(tags)"
     "select 1")))

(defn drop-old-tags-index
  "Drops the non-GIN tags index, which is never used (because nobody
  does equality comparisons against array columns)"
  []
  (jdbc/do-commands
   "DROP INDEX idx_catalog_resources_tags"))

(defn drop-classes-and-tags
  "Removes the `classes` and `tags` tables, as this information can be derived
  from resources."
  []
  (jdbc/do-commands
   (sql/drop-table-ddl :classes)
   (sql/drop-table-ddl :tags)))

(defn rename-fact-column
  "Renames the `fact` column on `certname_facts` to `name`, for consistency."
  []
  (jdbc/do-commands
   (if (sutils/postgres?)
     "ALTER TABLE certname_facts RENAME COLUMN fact TO name"
     "ALTER TABLE certname_facts ALTER COLUMN fact RENAME TO name")
   "ALTER INDEX idx_certname_facts_fact RENAME TO idx_certname_facts_name"))

(defn add-reports-tables
  "Add a resource_events and reports tables."
  []
  (jdbc/do-commands
   (sql/create-table-ddl
    :reports
    ["hash" "VARCHAR(40)" "NOT NULL" "PRIMARY KEY"]
    ["certname" "TEXT" "REFERENCES certnames(name)" "ON DELETE CASCADE"]
    ["puppet_version" "VARCHAR(40)" "NOT NULL"]
    ["report_format" "SMALLINT" "NOT NULL"]
    ["configuration_version" "VARCHAR(255)" "NOT NULL"]
    ["start_time" "TIMESTAMP WITH TIME ZONE" "NOT NULL"]
    ["end_time" "TIMESTAMP WITH TIME ZONE" "NOT NULL"]
    ["receive_time" "TIMESTAMP WITH TIME ZONE" "NOT NULL"])

   (sql/create-table-ddl
    :resource_events
    ["report" "VARCHAR(40)" "NOT NULL" "REFERENCES reports(hash)"
     "ON DELETE CASCADE"]
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
    ;; we can't set the "correct" primary key because `property` is nullable
    ;; (because of skipped resources).
    ;; We decided to just use a UNIQUE constraint for now, but another option
    ;; would be to split this out into two tables.
    ["CONSTRAINT constraint_resource_events_unique
       UNIQUE (report, resource_type, resource_title, property)"])

   "CREATE INDEX idx_reports_certname ON reports(certname)"

   ;; I presume we'll be doing a decent number of queries sorted by a timestamp,
   ;; and this seems like the most likely candidate out of the timestamp fields
   "CREATE INDEX idx_reports_end_time ON reports(end_time)"
   "CREATE INDEX idx_resource_events_report ON resource_events(report)"
   "CREATE INDEX idx_resource_events_resource_type ON resource_events(resource_type)"
   "CREATE INDEX idx_resource_events_resource_type_title ON resource_events(resource_type, resource_title)"
   "CREATE INDEX idx_resource_events_timestamp ON resource_events(timestamp)"))

(defn add-parameter-cache
  "Creates the new resource_params_cache table, and populates it using
  the existing parameters in the database."
  []
  ;; Create cache table
  (jdbc/do-commands
   (sql/create-table-ddl :resource_params_cache
                         ["resource" "VARCHAR(40)"]
                         ["parameters" "TEXT"]
                         ["PRIMARY KEY (resource)"]))

  (log/warn "Building resource parameters cache. This make take a few minutes, but faster resource queries are worth it.")

  ;; Loop over all parameters, and insert a cache entry for each resource
  (let [query    ["SELECT resource, name, value from resource_params ORDER BY resource"]
        collapse (fn [rows]
                   (let [resource (:resource (first rows))
                         params   (into {} (map #(vector (:name %) (json/parse-string (:value %))) rows))]
                     [resource params]))]

    (jdbc/with-query-results-cursor query
      (fn [rs]
        (let [param-sets (->> rs
                              (partition-by :resource)
                              (map collapse))]
          (doseq [[resource params] param-sets]
            (jdbc/insert! :resource_params_cache
                          {:resource   resource
                           :parameters (json/generate-string params)}))))))

  ;; Create NULL entries for resources that have no parameters
  (jdbc/do-commands
   "INSERT INTO resource_params_cache
    SELECT DISTINCT resource, NULL FROM catalog_resources WHERE NOT EXISTS
    (SELECT 1 FROM resource_params WHERE resource=catalog_resources.resource)"

   "ALTER TABLE catalog_resources ADD FOREIGN KEY (resource)
      REFERENCES resource_params_cache(resource) ON DELETE CASCADE"
   "ALTER TABLE resource_params ADD FOREIGN KEY (resource)
      REFERENCES resource_params_cache(resource) ON DELETE CASCADE"))

(defn add-event-status-index
  "Add an index to the `status` column of the event table."
  []
  (jdbc/do-commands
   "CREATE INDEX idx_resource_events_status ON resource_events(status)"))

(defn increase-puppet-version-field-length
  "Increase the length of the puppet_version field in the reports table, as we've
  encountered some version strings that are longer than 40 chars."
  []
  (jdbc/do-commands
   (condp = (:database @sutils/db-metadata)
     "PostgreSQL" "ALTER TABLE reports ALTER puppet_version TYPE VARCHAR(255)"
     "HSQL Database Engine" "ALTER TABLE reports ALTER puppet_version VARCHAR(255)"
     (throw (IllegalArgumentException.
             (format "Unsupported database engine '%s'"
                     (:database @sutils/db-metadata)))))))

(defn burgundy-schema-changes
  "Schema changes for the initial release of Burgundy. These include:

    - Add 'file' and 'line' columns to the event table
    - A column for the resource's containment path in the resource_events table
    - A column for the transaction uuid in the reports & catalogs tables
    - Renames the `sourcefile` and `sourceline` columns on the `catalog_resources`
      table to `file` and `line` for consistency.
    - Add index to 'property' column in resource_events table"
  []
  (jdbc/do-commands
   "ALTER TABLE resource_events ADD COLUMN file VARCHAR(1024) DEFAULT NULL"
   "ALTER TABLE resource_events ADD COLUMN line INTEGER DEFAULT NULL"

   (format "ALTER TABLE resource_events ADD containment_path %s" (sutils/sql-array-type-string "TEXT"))
   "ALTER TABLE resource_events ADD containing_class VARCHAR(255)"
   "CREATE INDEX idx_resource_events_containing_class ON resource_events(containing_class)"
   "CREATE INDEX idx_resource_events_property ON resource_events(property)"

   ;; It would be nice to change the transaction UUID column to NOT NULL in the future
   ;; once we stop supporting older versions of Puppet that don't have this field.
   "ALTER TABLE reports ADD COLUMN transaction_uuid VARCHAR(255) DEFAULT NULL"
   "CREATE INDEX idx_reports_transaction_uuid ON reports(transaction_uuid)"
   "ALTER TABLE catalogs ADD COLUMN transaction_uuid VARCHAR(255) DEFAULT NULL"
   "CREATE INDEX idx_catalogs_transaction_uuid ON catalogs(transaction_uuid)"

   (if (sutils/postgres?)
     "ALTER TABLE catalog_resources RENAME COLUMN sourcefile TO file"
     "ALTER TABLE catalog_resources ALTER COLUMN sourcefile RENAME TO file")
   (if (sutils/postgres?)
     "ALTER TABLE catalog_resources RENAME COLUMN sourceline TO line"
     "ALTER TABLE catalog_resources ALTER COLUMN sourceline RENAME TO line")))

(defn add-latest-reports-table
  "Add `latest_reports` table for easy lookup of latest report for each certname."
  []
  (jdbc/do-commands
   (sql/create-table-ddl :latest_reports
                          ["certname" "TEXT" "NOT NULL" "PRIMARY KEY"
                           "REFERENCES certnames(name)"
                           "ON DELETE CASCADE"]
                          ["report" "VARCHAR(40)" "NOT NULL"
                           "REFERENCES reports(hash)"
                           "ON DELETE CASCADE"])
   "CREATE INDEX idx_latest_reports_report ON latest_reports(report)"
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
  (jdbc/do-commands "DROP INDEX idx_catalogs_hash"
                    "DROP INDEX idx_certname_catalogs_certname"))

(defn drop-resource-tags-index
  "Remove the resource tags index, it can get very large and is not used"
  []
  (jdbc/do-commands "DROP INDEX IF EXISTS idx_catalog_resources_tags_gin"))

(defn use-bigint-instead-of-catalog-hash
  "This migration converts all catalog hash instances to use bigint sequences instead"
  []
  (jdbc/do-commands
   ;; catalogs: Create new table without constraints
   "CREATE TABLE catalogs_transform (
      id bigserial NOT NULL,
      hash character varying(40) NOT NULL,
      api_version integer NOT NULL,
      catalog_version text NOT NULL,
      transaction_uuid character varying(255) DEFAULT NULL)"

   ;; Catalogs: Insert data from old table
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
      tags " (sutils/sql-array-type-string "TEXT") " NOT NULL,
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
   (if (sutils/postgres?)
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
  (jdbc/do-commands
   (if (sutils/postgres?)
     "CREATE INDEX idx_catalog_resources_exported_true
         ON catalog_resources (exported) WHERE exported = true"
     "CREATE INDEX idx_catalog_resources_exported
         ON catalog_resources (exported)")))

(defn differential-edges
  "Convert edges so it becomes a 1 to many relationship with certnames
  instead of catalogs. This is so we can adequately do differential edge
  inserts/deletes otherwise this would prove difficult as catalogs is still
  incremental. Once catalogs and catalog_resources are converted to
  differential updates this table can be reassociated with catalogs if
  desired."
  []
  ;; Start by doing a garbage collect on catalogs, so there is a 1 to 1 mapping for edges
  (jdbc/delete! :catalogs
                ["NOT EXISTS (SELECT * FROM certname_catalogs cc
                                WHERE cc.catalog_id=catalogs.id)"])
  (jdbc/do-commands
   ;; Create the new edges table
   "CREATE TABLE edges_transform (
      certname text NOT NULL,
      source character varying(40) NOT NULL,
      target character varying(40) NOT NULL,
      type text NOT NULL)"

   ;; Migrate data from old table
   "INSERT INTO edges_transform (certname, source, target, type)
      SELECT cc.certname, e.source, e.target, e.type
        FROM edges e, catalogs c, certname_catalogs cc
        WHERE e.catalog_id = c.id and cc.catalog_id = c.id"

   ;; Drop old table
   "DROP TABLE edges"

   ;; Rename the new table
   "ALTER TABLE edges_transform RENAME TO edges"

   ;; Add foreign key constraints
   "ALTER TABLE edges
      ADD CONSTRAINT edges_certname_fkey FOREIGN KEY (certname)
          REFERENCES certnames (name)
          ON UPDATE NO ACTION ON DELETE CASCADE"

   ;; Add unique constraint to edge table
   "ALTER TABLE edges
      ADD CONSTRAINT edges_certname_source_target_type_unique_key UNIQUE (certname, source, target, type)"))

(defn differential-catalog-resources []

  (jdbc/delete! :catalogs
                ["NOT EXISTS (SELECT * FROM certname_catalogs cc
                                WHERE cc.catalog_id=catalogs.id)"])
  (jdbc/delete! :catalog_resources
                ["NOT EXISTS (SELECT * FROM certname_catalogs cc
                                WHERE cc.catalog_id=catalog_resources.catalog_id)"])
  (apply
   jdbc/do-commands
   (remove nil?
           [(sql/create-table-ddl
             :catalogs_transform
             ["id" "bigserial NOT NULL"]
             ["hash" "character varying(40) NOT NULL"]
             ["api_version" "INTEGER NOT NULL"]
             ["catalog_version" "TEXT NOT NULL"]
             ["transaction_uuid" "CHARACTER VARYING(255) DEFAULT NULL"]
             ["timestamp" "TIMESTAMP WITH TIME ZONE"]
             ["certname" "TEXT NOT NULL"])

            ;;Populate the new catalogs_transform table with data from
            ;;catalogs and certname_catalogs
            "INSERT INTO catalogs_transform (id, hash, api_version, catalog_version, transaction_uuid, timestamp, certname)
             SELECT c.id, c.hash, c.api_version, c.catalog_version, c.transaction_uuid, cc.timestamp, cc.certname
             FROM catalogs c INNER JOIN certname_catalogs cc on c.id = cc.catalog_id"

            "DROP TABLE certname_catalogs"

            ;;Can't drop catalogs with this constraint still attached
            "ALTER TABLE catalog_resources DROP CONSTRAINT catalog_resources_catalog_id_fkey"
            "DROP TABLE catalogs"

            ;;Rename catalogs_transform to catalogs, replace constraints
            "ALTER TABLE catalogs_transform RENAME to catalogs"

            (when (sutils/postgres?)
              "ALTER TABLE catalogs
               ADD CONSTRAINT catalogs_pkey PRIMARY KEY (id)")

            "ALTER TABLE catalog_resources
             ADD CONSTRAINT catalog_resources_catalog_id_fkey FOREIGN KEY (catalog_id)
             REFERENCES catalogs (id)
             ON UPDATE NO ACTION ON DELETE CASCADE"

            "ALTER TABLE catalogs
             ADD CONSTRAINT catalogs_certname_fkey FOREIGN KEY (certname)
             REFERENCES certnames (name)
             ON UPDATE NO ACTION ON DELETE CASCADE"

            "ALTER TABLE catalogs
             ADD CONSTRAINT catalogs_hash_key UNIQUE (hash)"
            "ALTER TABLE catalogs
             ADD CONSTRAINT catalogs_certname_key UNIQUE (certname)"

            "CREATE INDEX idx_catalogs_transaction_uuid
             ON catalogs (transaction_uuid)"

            "ALTER TABLE catalog_resources DROP CONSTRAINT catalog_resources_pkey"
            "ALTER TABLE catalog_resources ADD CONSTRAINT catalog_resources_pkey PRIMARY KEY (catalog_id, type, title)"])))

(defn reset-catalog-sequence-to-latest-id []
  (sutils/fix-identity-sequence "catalogs" "id"))

(defn add-environments []
  (jdbc/do-commands
   (sql/create-table-ddl :environments
                         ["id" "bigserial NOT NULL PRIMARY KEY"]
                         ["name" "TEXT NOT NULL" "UNIQUE"])

   "ALTER TABLE catalogs ADD environment_id integer"

   (if (sutils/postgres?)
     "ALTER TABLE catalogs ALTER COLUMN api_version DROP NOT NULL"
     "ALTER TABLE catalogs ALTER COLUMN api_version SET NULL")

   "ALTER TABLE certname_facts_metadata ADD environment_id integer"
   "ALTER TABLE reports ADD environment_id integer"

   "ALTER TABLE catalogs
    ADD CONSTRAINT catalogs_env_fkey FOREIGN KEY (environment_id)
    REFERENCES environments (id) ON UPDATE NO ACTION ON DELETE CASCADE"

   "CREATE INDEX idx_catalogs_env ON catalogs(environment_id)"

   "ALTER TABLE certname_facts_metadata
    ADD CONSTRAINT facts_env_fkey FOREIGN KEY (environment_id)
    REFERENCES environments (id) ON UPDATE NO ACTION ON DELETE CASCADE"

   "CREATE INDEX idx_certname_facts_env ON certname_facts_metadata(environment_id)"

   "ALTER TABLE reports
    ADD CONSTRAINT reports_env_fkey FOREIGN KEY (environment_id)
    REFERENCES environments (id) ON UPDATE NO ACTION ON DELETE CASCADE"

   "CREATE INDEX idx_reports_env ON reports(environment_id)"))

(defn add-report-status []
  (jdbc/do-commands
   (sql/create-table-ddl :report_statuses
                         ["id" "bigserial NOT NULL PRIMARY KEY"]
                         ["status" "TEXT NOT NULL" "UNIQUE"])

   "ALTER TABLE reports ADD status_id integer"

   "ALTER TABLE reports
    ADD CONSTRAINT reports_status_fkey FOREIGN KEY (status_id)
    REFERENCES report_statuses (id) ON UPDATE NO ACTION ON DELETE CASCADE"

   "CREATE INDEX idx_reports_status ON reports(status_id)"))

(defn add-producer-timestamps []
  (jdbc/do-commands
   "ALTER TABLE catalogs ADD producer_timestamp TIMESTAMP WITH TIME ZONE"
   "CREATE INDEX idx_catalogs_producer_timestamp ON catalogs(producer_timestamp)"))

(defn migrate-to-structured-facts
  "Pulls data from 'pre-structured' tables and moves to new."
  []
  (let [certname-facts-metadata (jdbc/query-to-vec "SELECT * FROM certname_facts_metadata")]
    (doseq [{:keys [certname timestamp environment_id]} certname-facts-metadata]
      (let [facts (->> certname
                       (jdbc/query-to-vec "SELECT * FROM certname_facts WHERE certname = ?")
                       (reduce #(assoc %1 (:name %2) (:value %2)) {}))
            environment (->> environment_id
                             (jdbc/query-to-vec "SELECT name FROM environments WHERE id = ?")
                             first
                             :name)]
        (when-not (empty? facts)
          (legacy/add-facts-27!
            {:certname (str certname)
             :values facts
             :timestamp timestamp
             :environment environment
             :producer_timestamp timestamp}))))))

(defn structured-facts []
  (jdbc/do-commands
   ;; -----------
   ;; VALUE_TYPES
   ;; -----------
   ;; Populate the value_types lookup table
   (sql/create-table-ddl :value_types
                         ["id" "bigint NOT NULL PRIMARY KEY"]
                         ["type" "character varying(32)"])
   "INSERT INTO value_types (id, type) values (0, 'string')"
   "INSERT INTO value_types (id, type) values (1, 'integer')"
   "INSERT INTO value_types (id, type) values (2, 'float')"
   "INSERT INTO value_types (id, type) values (3, 'boolean')"
   "INSERT INTO value_types (id, type) values (4, 'null')"
   "INSERT INTO value_types (id, type) values (5, 'json')"

   ;; ----------
   ;; FACT_PATHS
   ;; ----------
   "CREATE SEQUENCE fact_paths_id_seq CYCLE"

   (sql/create-table-ddl
    :fact_paths
    ["id" "bigint NOT NULL PRIMARY KEY DEFAULT nextval('fact_paths_id_seq')"]
    ["value_type_id" "bigint NOT NULL"]
    ["depth" "int NOT NULL"]
    ["name" "varchar(1024)"]
    ["path" "text NOT NULL"])

   "ALTER TABLE fact_paths ADD CONSTRAINT fact_paths_path_type_id_key
      UNIQUE (path, value_type_id)"
   "CREATE INDEX fact_paths_value_type_id ON fact_paths(value_type_id)"
   "CREATE INDEX fact_paths_name ON fact_paths(name)"
   "ALTER TABLE fact_paths ADD CONSTRAINT fact_paths_value_type_id
      FOREIGN KEY (value_type_id)
      REFERENCES value_types(id) ON UPDATE RESTRICT ON DELETE RESTRICT"

   ;; -----------
   ;; FACT_VALUES
   ;; -----------
   "CREATE SEQUENCE fact_values_id_seq CYCLE"

   (sql/create-table-ddl
    :fact_values
    ["id" "bigint NOT NULL PRIMARY KEY DEFAULT nextval('fact_values_id_seq')"]
    ["path_id" "bigint NOT NULL"]
    ["value_type_id" "bigint NOT NULL"]
    ["value_hash" "varchar(40) NOT NULL"]
    ["value_integer" "bigint"]
    ["value_float" "double precision"]
    ["value_string" "text"]
    ["value_boolean" "boolean"]
    ["value_json" "text"])

   "ALTER TABLE fact_values ADD CONSTRAINT fact_values_path_id_value_key
      UNIQUE (path_id, value_type_id, value_hash)"
   "ALTER TABLE fact_values ADD CONSTRAINT fact_values_path_id_fk
      FOREIGN KEY (path_id) REFERENCES fact_paths (id) MATCH SIMPLE
      ON UPDATE RESTRICT ON DELETE RESTRICT"
   "ALTER TABLE fact_values ADD CONSTRAINT fact_values_value_type_id_fk
      FOREIGN KEY (value_type_id) REFERENCES value_types (id) MATCH SIMPLE
      ON UPDATE RESTRICT ON DELETE RESTRICT"
   ;; For efficient operator querying with <, >, <= and >=
   "CREATE INDEX fact_values_value_integer_idx ON fact_values(value_integer)"
   "CREATE INDEX fact_values_value_float_idx ON fact_values(value_float)"

   ;; --------
   ;; FACTSETS
   ;; --------
   "CREATE SEQUENCE factsets_id_seq CYCLE"

   (sql/create-table-ddl
    :factsets
    ["id" "bigint NOT NULL PRIMARY KEY DEFAULT nextval('factsets_id_seq')"]
    ["certname" "text NOT NULL"]
    ["timestamp" "timestamp with time zone NOT NULL"]
    ["environment_id" "bigint"]
    ["producer_timestamp" "timestamp with time zone"])

   "ALTER TABLE factsets ADD CONSTRAINT factsets_certname_fk
      FOREIGN KEY (certname) REFERENCES certnames(name)
      ON UPDATE CASCADE ON DELETE CASCADE"
   "ALTER TABLE factsets ADD CONSTRAINT factsets_environment_id_fk
      FOREIGN KEY (environment_id) REFERENCES environments(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT"
   "ALTER TABLE factsets ADD CONSTRAINT factsets_certname_idx
      UNIQUE (certname)"

   ;; -----
   ;; FACTS
   ;; -----
   (sql/create-table-ddl :facts
                         ["factset_id" "bigint NOT NULL"]
                         ["fact_value_id" "bigint NOT NULL"])

   "ALTER TABLE facts ADD CONSTRAINT facts_factset_id_fact_value_id_key
      UNIQUE (factset_id, fact_value_id)"
   "ALTER TABLE facts ADD CONSTRAINT fact_value_id_fk
      FOREIGN KEY (fact_value_id) REFERENCES fact_values(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT"
   "ALTER TABLE facts ADD CONSTRAINT factset_id_fk
      FOREIGN KEY (factset_id) REFERENCES factsets(id)
      ON UPDATE CASCADE ON DELETE CASCADE")

  (migrate-to-structured-facts)

  (jdbc/do-commands
   "DROP TABLE certname_facts"
   "DROP TABLE certname_facts_metadata"))

(defn structured-facts-deferrable-constraints []

  ;;This migration is switching from a GC background thread to
  ;;removing fact values as they are orphaned. Before switching to
  ;;that model (which only cleans up the specific fact values it
  ;;orphans) we need to clean up any ones that have appeared between
  ;;the last run and the current run.
  (jdbc/delete! :fact_values
                ["ID NOT IN (SELECT DISTINCT fact_value_id FROM facts)"])
  (jdbc/delete! :fact_paths
                ["ID NOT IN (SELECT path_id FROM fact_values)"])

  (when (sutils/postgres?)
    (jdbc/do-commands

     "ALTER TABLE fact_values DROP CONSTRAINT fact_values_path_id_fk"
     (str "ALTER TABLE fact_values ADD CONSTRAINT fact_values_path_id_fk
           FOREIGN KEY (path_id) REFERENCES fact_paths (id) MATCH SIMPLE
           ON UPDATE NO ACTION ON DELETE NO ACTION
           DEFERRABLE")

     "ALTER TABLE facts DROP CONSTRAINT fact_value_id_fk"
     (str "ALTER TABLE facts ADD CONSTRAINT fact_value_id_fk
           FOREIGN KEY (fact_value_id) REFERENCES fact_values(id)
           ON UPDATE NO ACTION  ON DELETE NO ACTION
           DEFERRABLE")))

  (jdbc/do-commands
   "CREATE INDEX fact_value_id_idx ON facts(fact_value_id)"))

(defn switch-value-string-index-to-gin
  "This drops the fact_values_string_trgm index so that it can be recreated
  as a GIN index."
  []
  (when (and (sutils/postgres?)
             (sutils/index-exists? "fact_values_string_trgm"))
    (jdbc/do-commands
      "DROP INDEX fact_values_string_trgm")))

(defn lift-fact-paths-into-facts
  "Pairs paths and values directly in facts, i.e. change facts from (id
  value) to (id path value)."
  []
  (jdbc/do-commands

   "CREATE TABLE facts_unique_transform
      (factset_id bigint NOT NULL,
       fact_path text NOT NULL,
       fact_value_hash varchar(40) NOT NULL)"

   "INSERT INTO facts_unique_transform (factset_id, fact_path, fact_value_hash)
      SELECT f.factset_id, fp.path, fv.value_hash
        FROM facts f
        INNER JOIN fact_values fv on f.fact_value_id = fv.id
        INNER JOIN fact_paths fp on fv.path_id = fp.id"

   "DROP TABLE facts"

   ;; We need these indexes for the upcoming deletions.
   ;; A deletion like this doesn't need the index, but it only works
   ;; with PostgreSQL:
   ;;   DELETE FROM fact_values t1 USING fact_values t2
   ;;     WHERE t1.value_hash = t2.value_hash AND t1.id > t2.id
   "CREATE INDEX fact_path_path_idx ON fact_paths(path)"
   "CREATE INDEX fact_values_value_hash_idx ON fact_values(value_hash)"

   ;; Remove all the orphaned duplicates (all but the row in each set
   ;; with min-id).
   "ALTER TABLE fact_values DROP CONSTRAINT fact_values_path_id_fk"
   "DELETE FROM fact_paths t1
      WHERE t1.id <> (SELECT MIN(t2.id) FROM fact_paths t2
                        WHERE t1.path = t2.path)"
   "DELETE FROM fact_values t1
      WHERE t1.id <> (SELECT MIN(t2.id) FROM fact_values t2
                        WHERE t1.value_hash = t2.value_hash)"

   ;; Q: Is this right, or do we just keep the (redundant) indexes?
   "DROP INDEX fact_path_path_idx"
   "DROP INDEX fact_values_value_hash_idx"
   "ALTER TABLE fact_paths
      ADD CONSTRAINT fact_paths_path_key UNIQUE (path)"
   "ALTER TABLE fact_values
      ADD CONSTRAINT fact_values_value_hash_key UNIQUE (value_hash)"

   "CREATE TABLE facts_transform
      (factset_id bigint NOT NULL,
       fact_path_id bigint NOT NULL,
       fact_value_id bigint NOT NULL)"

   ;; Patch up facts refrences to refer to the min id path/value
   ;; wherever there's more than one option.
   "INSERT INTO facts_transform (factset_id, fact_path_id, fact_value_id)
      SELECT f.factset_id, fp.id, fv.id
        FROM facts_unique_transform f
        INNER JOIN fact_paths fp on f.fact_path = fp.path
        INNER JOIN fact_values fv on f.fact_value_hash = fv.value_hash;"

   "DROP TABLE facts_unique_transform"

   "ALTER TABLE facts_transform
      ADD CONSTRAINT facts_factset_id_fact_path_id_fact_key
        UNIQUE (factset_id, fact_path_id)"

   "ALTER TABLE facts_transform
      ADD CONSTRAINT factset_id_fk
        FOREIGN KEY (factset_id) REFERENCES factsets(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE"

   "ALTER TABLE facts_transform
         ADD CONSTRAINT fact_path_id_fk
           FOREIGN KEY (fact_path_id) REFERENCES fact_paths(id)"
   "ALTER TABLE facts_transform
         ADD CONSTRAINT fact_value_id_fk
           FOREIGN KEY (fact_value_id) REFERENCES fact_values(id)"

   "ALTER TABLE facts_transform RENAME TO facts"
   "CREATE INDEX facts_fact_path_id_idx ON facts(fact_path_id)"
   "CREATE INDEX facts_fact_value_id_idx ON facts(fact_value_id)"

   (if (sutils/postgres?) "ANALYZE facts" "SELECT 1")

   ;; These are for the more pedantic HSQLDB.
   "ALTER TABLE fact_paths DROP CONSTRAINT fact_paths_path_type_id_key"
   "ALTER TABLE fact_paths DROP CONSTRAINT fact_paths_value_type_id"
   "DROP INDEX fact_paths_value_type_id"
   "ALTER TABLE fact_values DROP CONSTRAINT fact_values_path_id_value_key"

   "ALTER TABLE fact_paths DROP COLUMN value_type_id"
   "ALTER TABLE fact_values DROP COLUMN path_id"))

(defn version-2yz-to-300-migration
  ;; This migration includes:
  ;;   Insertion of the factsets hash column
  ;;   Using a report_id (surrogate key) instead of the reports.hash (natural key)
  ;;   Insert a noop column into reports
  ;;   Drop latest_report table, merge with certnames
  ;;   Change name to certname where inconsistent
  ;;   Insert reports metrics and logs
  ;;   Ensuring producer_timestamp is NOT NULL
  ;;   Changing the types of hashes and uuids in postgres to bytea and uuid respectively
  []
  (let [hash-type (if (sutils/postgres?) "bytea" "varchar(40)")
        uuid-type (if (sutils/postgres?) "uuid" "varchar(255)")
        json-type (if (sutils/postgres?) "json" "text")
        munge-hash (if (sutils/postgres?) (fn [column] (format "('\\x' || %s)::bytea" column)) identity)
        munge-uuid (if (sutils/postgres?) (fn [column] (format "%s::uuid" column)) identity)]

    (jdbc/do-commands
      "UPDATE catalogs SET producer_timestamp=timestamp
         WHERE producer_timestamp IS NULL"
      "UPDATE factsets SET producer_timestamp=timestamp
         WHERE producer_timestamp IS NULL"

      (sql/create-table-ddl
       :factsets_transform
       ["id" "bigint NOT NULL DEFAULT nextval('factsets_id_seq')"]
       ["certname" "text NOT NULL"]
       ["timestamp" "timestamp with time zone NOT NULL"]
       ["environment_id" "bigint"]
       ["hash" hash-type]
       ["producer_timestamp" "timestamp with time zone NOT NULL"])

      "INSERT INTO factsets_transform
         (id, certname, timestamp, environment_id, producer_timestamp)
         SELECT id, certname, timestamp, environment_id, timestamp
           FROM factsets fs"

      (sql/create-table-ddl
       :fact_values_transform
       ["id" "bigint NOT NULL DEFAULT nextval('fact_values_id_seq')"]
       ["value_hash" hash-type "NOT NULL"]
       ["value_type_id" "bigint NOT NULL"]
       ["value_integer" "bigint"]
       ["value_float" "double precision"]
       ["value_string" "text"]
       ["value_boolean" "boolean"]
       ["value_json" "text"])

      (str "INSERT INTO fact_values_transform
              (id, value_hash, value_type_id, value_integer, value_float,
               value_string, value_boolean, value_json)
              SELECT id, " (munge-hash "value_hash") ", value_type_id,
                     value_integer, value_float, value_string, value_boolean,
                     value_json
                FROM fact_values")

      (sql/create-table-ddl :resource_params_cache_transform
                            ["resource" hash-type "NOT NULL"]
                            ["parameters" "TEXT"])

      (str "INSERT INTO resource_params_cache_transform
              (resource, parameters)
              SELECT " (munge-hash "resource") ", parameters
                FROM resource_params_cache")

      (sql/create-table-ddl
       :catalog_resources_transform
       ["catalog_id" "bigint NOT NULL"]
       ["resource" hash-type "NOT NULL"]
       ["tags" (sutils/sql-array-type-string "TEXT") "NOT NULL"]
       ["type" "TEXT" "NOT NULL"]
       ["title" "TEXT" "NOT NULL"]
       ["exported" "BOOLEAN" "NOT NULL"]
       ["file" "TEXT"]
       ["line" "INT"])

      (str "INSERT INTO catalog_resources_transform
              (resource, catalog_id, tags, type, title, exported, file, line)
              SELECT " (munge-hash "resource") ", catalog_id, tags, type, title,
                       exported, file, line
                FROM catalog_resources")

      (sql/create-table-ddl :resource_params_transform
                            ["resource" hash-type "NOT NULL"]
                            ["name"  "TEXT" "NOT NULL"]
                            ["value" "TEXT" "NOT NULL"])

      (str "INSERT INTO resource_params_transform
              (resource, name, value)
              SELECT " (munge-hash "resource") ", name, value
                FROM resource_params")

      (sql/create-table-ddl :edges_transform
                            ["certname" "TEXT" "NOT NULL"]
                            ["source" hash-type "NOT NULL"]
                            ["target" hash-type "NOT NULL"]
                            ["type" "TEXT" "NOT NULL"])

      (str "INSERT INTO edges_transform (certname, source, target, type)
              SELECT certname, " (munge-hash "source") ",
                   " (munge-hash "target") ", type
              FROM edges")

      "CREATE SEQUENCE catalogs_id_seq CYCLE"

      (sql/create-table-ddl
       :catalogs_transform
       ["id" "bigint NOT NULL DEFAULT nextval('catalogs_id_seq')"]
       ["hash" hash-type "NOT NULL"]
       ["transaction_uuid" uuid-type]
       ["certname" "text NOT NULL"]
       ["producer_timestamp" "timestamp with time zone NOT NULL"]
       ["api_version" "INTEGER NOT NULL"]
       ["timestamp" "TIMESTAMP WITH TIME ZONE"]
       ["catalog_version" "TEXT NOT NULL"]
       ["environment_id" "bigint"])

      (str "INSERT INTO catalogs_transform
              (id, hash, transaction_uuid, certname, producer_timestamp,
               api_version, timestamp, catalog_version, environment_id)
              SELECT id, " (munge-hash "hash") ",
                   " (munge-uuid "transaction_uuid") ", certname,
                     producer_timestamp, api_version, timestamp,
                     catalog_version, environment_id
                FROM catalogs")

      ;; Migrate to report id
      "CREATE SEQUENCE reports_id_seq CYCLE"

      (sql/create-table-ddl
       :reports_transform
       ["id" "bigint NOT NULL DEFAULT nextval('reports_id_seq')"]
       ["hash" hash-type "NOT NULL"]
       ["transaction_uuid" uuid-type]
       ["certname" "text NOT NULL"]
       ["puppet_version" "varchar(255) NOT NULL"]
       ["report_format" "smallint NOT NULL"]
       ["configuration_version" "varchar(255) NOT NULL"]
       ["start_time" "timestamp with time zone NOT NULL"]
       ["end_time" "timestamp with time zone NOT NULL"]
       ["receive_time" "timestamp with time zone NOT NULL"]
       ;; Insert a column in reports to be populated by boolean noop flag
       ["noop" "boolean"]
       ["environment_id" "bigint"]
       ["status_id" "bigint"]
       ;; Insert columns in reports to be populated by metrics and logs.
       ;; Text for hsql, JSON for postgres.
       ["metrics" json-type]
       ["logs" json-type])

      (str "INSERT INTO reports_transform (
            hash, certname, puppet_version, report_format, configuration_version,
            start_time, end_time, receive_time, transaction_uuid, environment_id,
            status_id)
            SELECT " (munge-hash "hash") ", certname, puppet_version, report_format,
            configuration_version, start_time, end_time, receive_time, "
            (munge-uuid "transaction_uuid") ", environment_id, status_id
            FROM reports")

      (sql/create-table-ddl
       :resource_events_transform
       ["report_id" "bigint NOT NULL"]
       ["status" "varchar(40) NOT NULL"]
       ["timestamp" "timestamp with time zone NOT NULL"]
       ["resource_type" "text NOT NULL"]
       ["resource_title" "text NOT NULL"]
       ["property" "varchar (40)"]
       ["new_value" "text"]
       ["old_value" "text"]
       ["message" "text"]
       ["file" "varchar(1024) DEFAULT NULL"]
       ["line" "integer"]
       ["containment_path" (sutils/sql-array-type-string "TEXT")]
       ["containing_class" "varchar(255)"])

      (str "INSERT INTO resource_events_transform (
            report_id, status, timestamp, resource_type, resource_title, property,
            new_value, old_value, message, file, line, containment_path,
            containing_class)
            SELECT rt.id, status, timestamp, resource_type, resource_title,
            property, new_value, old_value, message, file, line, containment_path,
            containing_class
            FROM resource_events AS re
            INNER JOIN reports_transform rt on " (munge-hash "re.report") " = rt.hash")

      (sql/create-table-ddl
       :certnames_transform
       ;; Rename the 'name' column of certnames to 'certname'.
       ["certname" "text NOT NULL"]
       ["latest_report_id" "bigint"]
       ["deactivated" "timestamp with time zone"])

      (str "INSERT INTO certnames_transform(certname,latest_report_id,deactivated)
            SELECT c.name, rt.id as latest_report_id, c.deactivated FROM
            certnames c left outer join latest_reports lr on c.name=lr.certname
            left outer join reports_transform rt on " (munge-hash "lr.report") "=rt.hash")

      "DROP TABLE edges"
      "DROP TABLE catalog_resources"
      "DROP TABLE resource_params"
      "DROP TABLE resource_params_cache"
      "DROP TABLE catalogs"
      "ALTER TABLE facts DROP CONSTRAINT fact_value_id_fk"
      "DROP TABLE fact_values"
      "ALTER TABLE facts DROP CONSTRAINT factset_id_fk"
      "DROP TABLE factsets"
      "DROP TABLE latest_reports"
      "DROP TABLE certnames CASCADE"
      "DROP TABLE resource_events"
      "DROP TABLE reports"

      "ALTER TABLE catalog_resources_transform RENAME TO catalog_resources"
      "ALTER TABLE resource_params_transform RENAME TO resource_params"
      "ALTER TABLE resource_params_cache_transform RENAME TO resource_params_cache"
      "ALTER TABLE catalogs_transform RENAME TO catalogs"
      "ALTER TABLE fact_values_transform RENAME TO fact_values"
      "ALTER TABLE factsets_transform RENAME TO factsets"
      "ALTER TABLE certnames_transform RENAME TO certnames"
      "ALTER TABLE edges_transform RENAME TO edges"
      "ALTER TABLE resource_events_transform RENAME to resource_events"
      "ALTER TABLE reports_transform RENAME to reports"

      "ALTER TABLE edges
       ADD CONSTRAINT edges_certname_source_target_type_unique_key
         UNIQUE (certname, source, target, type)"

      "CREATE INDEX idx_catalogs_transaction_uuid ON catalogs(transaction_uuid)"
      "CREATE INDEX idx_catalogs_producer_timestamp ON catalogs(producer_timestamp)"
      "CREATE INDEX idx_catalogs_env ON catalogs(environment_id)"
      "ALTER TABLE catalogs ADD CONSTRAINT catalogs_hash_key UNIQUE (hash)"
      "ALTER TABLE catalogs ADD CONSTRAINT catalogs_certname_key UNIQUE (certname)"
      "ALTER TABLE catalogs ADD CONSTRAINT catalogs_pkey PRIMARY KEY (id)"
      "ALTER TABLE catalogs
       ADD CONSTRAINT catalogs_env_fkey FOREIGN KEY (environment_id)
       REFERENCES environments (id) ON UPDATE NO ACTION ON DELETE CASCADE"
      "ALTER TABLE catalog_resources
       ADD CONSTRAINT catalog_resources_catalog_id_fkey FOREIGN KEY (catalog_id)
       REFERENCES catalogs (id)
       ON UPDATE NO ACTION ON DELETE CASCADE"

      "ALTER TABLE resource_params ADD CONSTRAINT resource_params_pkey
         PRIMARY KEY (resource, name)"
      "CREATE INDEX idx_resources_params_resource ON resource_params(resource)"
      "CREATE INDEX idx_resources_params_name ON resource_params(name)"

      "ALTER TABLE catalog_resources ADD CONSTRAINT catalog_resources_pkey
         PRIMARY KEY (catalog_id, type, title)"
      (if (sutils/postgres?)
        "CREATE INDEX idx_catalog_resources_exported_true
           ON catalog_resources (exported) WHERE exported = true"
        "CREATE INDEX idx_catalog_resources_exported
           ON catalog_resources (exported)")
      "CREATE INDEX idx_catalog_resources_type ON catalog_resources(type)"
      "CREATE INDEX idx_catalog_resources_resource
         ON catalog_resources(resource)"
      "CREATE INDEX idx_catalog_resources_type_title
         ON catalog_resources(type,title)"

      "ALTER TABLE resource_params_cache
         ADD CONSTRAINT resource_params_cache_pkey PRIMARY KEY (resource)"
      "ALTER TABLE catalog_resources
         ADD CONSTRAINT catalog_resources_resource_fkey FOREIGN KEY (resource)
           REFERENCES resource_params_cache (resource)
           ON UPDATE NO ACTION ON DELETE CASCADE"
      "ALTER TABLE resource_params
         ADD CONSTRAINT resource_params_resource_fkey FOREIGN KEY (resource)
           REFERENCES resource_params_cache (resource)
           ON UPDATE NO ACTION ON DELETE CASCADE"

      "CREATE INDEX fact_values_value_integer_idx ON fact_values(value_integer)"
      "CREATE INDEX fact_values_value_float_idx ON fact_values(value_float)"
      "ALTER TABLE fact_values ADD CONSTRAINT fact_values_value_type_id_fk
         FOREIGN KEY (value_type_id) REFERENCES value_types (id) MATCH SIMPLE
         ON UPDATE RESTRICT ON DELETE RESTRICT"
      "ALTER TABLE fact_values ADD CONSTRAINT fact_values_value_hash_key
         UNIQUE (value_hash)"
      "ALTER TABLE fact_values ADD CONSTRAINT fact_values_pkey PRIMARY KEY (id)"
      "ALTER TABLE facts ADD CONSTRAINT fact_value_id_fk
         FOREIGN KEY (fact_value_id) REFERENCES fact_values(id)
         ON UPDATE RESTRICT ON DELETE RESTRICT"

      "ALTER TABLE reports ADD CONSTRAINT reports_pkey PRIMARY KEY (id)"
      "CREATE INDEX reports_certname_idx ON reports(certname)"
      "CREATE INDEX reports_end_time_idx ON reports(end_time)"
      "CREATE INDEX reports_environment_id_idx ON reports(environment_id)"
      "CREATE INDEX reports_status_id_idx ON reports(status_id)"
      "CREATE INDEX reports_transaction_uuid_idx ON reports(transaction_uuid)"
      "ALTER TABLE reports ADD CONSTRAINT reports_env_fkey
         FOREIGN KEY (environment_id) REFERENCES environments(id)
         ON DELETE CASCADE"
      "ALTER TABLE reports ADD CONSTRAINT reports_status_fkey
         FOREIGN KEY (status_id) REFERENCES report_statuses(id)
         ON DELETE CASCADE"
      "ALTER TABLE reports ADD CONSTRAINT reports_hash_key UNIQUE (hash)"

      "ALTER TABLE factsets ADD CONSTRAINT factsets_pkey PRIMARY KEY (id)"
      "ALTER TABLE factsets ADD CONSTRAINT factsets_environment_id_fk
       FOREIGN KEY (environment_id) REFERENCES environments(id)
       ON UPDATE RESTRICT ON DELETE RESTRICT"
      "ALTER TABLE facts ADD CONSTRAINT factset_id_fk
       FOREIGN KEY (factset_id) REFERENCES factsets(id)
       ON UPDATE CASCADE ON DELETE CASCADE"
      "ALTER TABLE factsets ADD CONSTRAINT factsets_certname_idx
         UNIQUE (certname)"
      "ALTER TABLE factsets ADD CONSTRAINT factsets_hash_key UNIQUE (hash)"

      "ALTER TABLE resource_events ADD CONSTRAINT resource_events_unique
         UNIQUE (report_id, resource_type, resource_title, property)"
      "ALTER TABLE resource_events ADD CONSTRAINT resource_events_report_id_fkey
         FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE"

      "ALTER TABLE certnames ADD CONSTRAINT certnames_pkey
         PRIMARY KEY (certname)"
      "CREATE INDEX certnames_latest_report_id_idx
         ON certnames(latest_report_id)"
      "ALTER TABLE edges ADD CONSTRAINT edges_certname_fkey
         FOREIGN KEY (certname) REFERENCES certnames(certname)
         ON UPDATE NO ACTION ON DELETE CASCADE"
      "ALTER TABLE catalogs ADD CONSTRAINT catalogs_certname_fkey
         FOREIGN KEY (certname) REFERENCES certnames(certname)
         ON UPDATE NO ACTION ON DELETE CASCADE"
      "ALTER TABLE factsets ADD CONSTRAINT factsets_certname_fk
         FOREIGN KEY (certname) REFERENCES certnames(certname)
         ON UPDATE CASCADE ON DELETE CASCADE"
      "ALTER TABLE reports ADD CONSTRAINT reports_certname_fkey
         FOREIGN KEY (certname) REFERENCES certnames(certname)
         ON DELETE CASCADE"

      (if (sutils/postgres?)
        "ALTER TABLE certnames ADD CONSTRAINT certnames_reports_id_fkey
           FOREIGN KEY (latest_report_id) REFERENCES reports(id)
           ON DELETE SET NULL"
        "select 1"))))

(defn add-expired-to-certnames
  "Add a 'expired' column to the 'certnames' table, to track
  which nodes have been automatically expired because of inactivity."
  []
  (jdbc/do-commands
   "ALTER TABLE certnames ADD COLUMN expired TIMESTAMP WITH TIME ZONE DEFAULT NULL"))

(defn coalesce-values
  [value-keys row]
  (let [updated-row (update-in row [:value_json] json/parse-string)]
    (->> (first (remove nil? (vals (select-keys updated-row value-keys))))
         (assoc updated-row :value))))

(defn update-value-json
  [{:keys [id value] :as arg}]
  (jdbc/update! :fact_values
                {:value_json (json/generate-string value)}
                ["id=?" id]))

(defn coalesce-fact-values
  []
  (let [query ["select * from fact_values"]
        value-keys [:value_string :value_integer
                    :value_json :value_boolean
                    :value_float]]
    (jdbc/with-query-results-cursor query
      (fn [rs]
        (->> rs
             (map (partial coalesce-values value-keys))
             (map update-value-json)
             dorun)))
    (jdbc/do-commands
      (if (sutils/postgres?)
        "ALTER TABLE fact_values RENAME COLUMN value_json TO value"
        "ALTER TABLE fact_values ALTER COLUMN value_json RENAME TO value"))))

(defn add-producer-timestamp-to-reports []
  (jdbc/do-commands
   "ALTER TABLE reports ADD producer_timestamp TIMESTAMP WITH TIME ZONE"
   "UPDATE reports SET producer_timestamp=end_time"
   "ALTER TABLE reports ALTER COLUMN producer_timestamp SET NOT NULL"
   "CREATE INDEX idx_reports_producer_timestamp
      ON reports(producer_timestamp)"))

(defn add-certname-id-to-certnames
  []
  (jdbc/do-commands
   "CREATE SEQUENCE certname_id_seq CYCLE"

   (sql/create-table-ddl
    :certnames_transform
    ;; Rename the 'name' column of certnames to 'certname'.
    ["id" "bigint NOT NULL PRIMARY KEY default nextval('certname_id_seq')"]
    ["certname" "text NOT NULL UNIQUE"]
    ["latest_report_id" "bigint"]
    ["deactivated" "timestamp with time zone"]
    ["expired" "timestamp with time zone"])

   "INSERT INTO certnames_transform
     (certname, latest_report_id, deactivated, expired)
     SELECT certname, latest_report_id, deactivated, expired
     FROM certnames"
   "ALTER TABLE certnames DROP CONSTRAINT certnames_pkey CASCADE"
   "DROP TABLE certnames"
   "ALTER TABLE certnames_transform RENAME to certnames"
   "ALTER TABLE catalogs ADD CONSTRAINT catalogs_certname_fkey
     FOREIGN KEY (certname) REFERENCES certnames(certname)
     ON UPDATE NO ACTION ON DELETE CASCADE"
   "ALTER TABLE factsets ADD CONSTRAINT factsets_certname_fk
     FOREIGN KEY (certname) REFERENCES certnames(certname)
     ON UPDATE CASCADE ON DELETE CASCADE"
   "ALTER TABLE reports ADD CONSTRAINT reports_certname_fkey
     FOREIGN KEY (certname)
     REFERENCES certnames(certname) ON DELETE CASCADE"

   (if (sutils/postgres?)
     "ALTER TABLE certnames ADD CONSTRAINT certnames_reports_id_fkey
        FOREIGN KEY (latest_report_id)
        REFERENCES reports(id) ON DELETE SET NULL"
     "select 1")))

(defn add-certname-id-to-resource-events
  []
  (jdbc/do-commands
   (sql/create-table-ddl
    :resource_events_transform
    ["report_id" "bigint NOT NULL"]
    ["certname_id" "bigint NOT NULL"]
    ["status" "varchar(40) NOT NULL"]
    ["timestamp" "timestamp with time zone NOT NULL"]
    ["resource_type" "text NOT NULL"]
    ["resource_title" "text NOT NULL"]
    ["property" "varchar (40)"]
    ["new_value" "text"]
    ["old_value" "text"]
    ["message" "text"]
    ["file" "varchar(1024) DEFAULT NULL"]
    ["line" "integer"]
    ["containment_path" (sutils/sql-array-type-string "TEXT")]
    ["containing_class" "varchar(255)"])

   "INSERT INTO resource_events_transform (
       report_id, certname_id, status, timestamp, resource_type, resource_title,
       property, new_value, old_value, message, file, line, containment_path,
       containing_class)
       SELECT reports.id as report_id,certnames.id as certname_id, status,
       timestamp, resource_type, resource_title, property, new_value, old_value,
       message, file, line, containment_path, containing_class
       FROM resource_events as re
       inner join reports on re.report_id = reports.id
       inner join certnames on reports.certname=certnames.certname"

   "DROP TABLE resource_events"
   "ALTER TABLE resource_events_transform RENAME to resource_events"
   "CREATE INDEX resource_events_resource_timestamp ON
     resource_events(resource_type, resource_title, timestamp)"

   "ALTER TABLE resource_events ADD CONSTRAINT resource_events_unique
     UNIQUE (report_id, resource_type, resource_title, property)"
   "CREATE INDEX resource_events_containing_class_idx
     ON resource_events(containing_class)"
   "CREATE INDEX resource_events_property_idx ON resource_events(property)"
   "CREATE INDEX resource_events_reports_id_idx ON resource_events(report_id)"
   "CREATE INDEX resource_events_resource_type_idx
     ON resource_events(resource_type)"
   "CREATE INDEX resource_events_resource_title_idx
     ON resource_events(resource_title)"
   "CREATE INDEX resource_events_status_idx ON resource_events(status)"
   "CREATE INDEX resource_events_timestamp_idx ON resource_events(timestamp)"
   "ALTER TABLE resource_events ADD CONSTRAINT resource_events_report_id_fkey
     FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE"))

(defn rename-environments-name-to-environment
  []
  (jdbc/do-commands
    (if (sutils/postgres?)
      "ALTER TABLE environments RENAME COLUMN name TO environment"
      "ALTER TABLE environments ALTER COLUMN name RENAME TO environment")))

(defn rename-column
  [table old-name new-name]
  (let [rename-str (if (sutils/postgres?)
                     "ALTER TABLE %s RENAME COLUMN %s TO %s"
                     "ALTER TABLE %s ALTER COLUMN %s RENAME TO %s")]
    (format rename-str table old-name new-name)))

(defn add-jsonb-columns-for-metrics-and-logs
  []
  (let [jsonb-type (if (sutils/postgres?) "jsonb" "text")]
    (jdbc/do-commands
      (rename-column "reports" "metrics" "metrics_json")
      (rename-column "reports" "logs" "logs_json")
      (format "ALTER TABLE reports ADD COLUMN metrics %s DEFAULT NULL" jsonb-type)
      (format "ALTER TABLE reports ADD COLUMN logs %s DEFAULT NULL" jsonb-type)
      (format "ALTER TABLE reports ADD COLUMN resources %s DEFAULT NULL" jsonb-type))))

(def migrations
  "The available migrations, as a map from migration version to migration function."
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
   18 add-index-on-exported-column
   19 differential-edges
   20 differential-catalog-resources
   21 reset-catalog-sequence-to-latest-id
   22 add-environments
   23 add-report-status
   24 add-producer-timestamps
   25 structured-facts
   26 structured-facts-deferrable-constraints
   27 switch-value-string-index-to-gin
   28 lift-fact-paths-into-facts
   29 version-2yz-to-300-migration
   30 add-expired-to-certnames
   31 coalesce-fact-values
   32 add-producer-timestamp-to-reports
   33 add-certname-id-to-certnames
   34 add-certname-id-to-resource-events
   ;; This dummy migration ensures that even databases that were up to
   ;; date when the "vacuum analyze" code was added to migrate! will
   ;; still analyze their existing databases.
   35 (fn [] true)
   36 rename-environments-name-to-environment
   37 add-jsonb-columns-for-metrics-and-logs
   })

(def desired-schema-version (apply max (keys migrations)))

(defn record-migration!
  "Records a migration by storing its version in the schema_migrations table,
  along with the time at which the migration was performed."
  [version]
  {:pre [(integer? version)]}
  (jdbc/do-prepared
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
          results (jdbc/with-db-transaction []  (query-to-vec query))]
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

(defn- sql-or-die [f]
  "Calls (f) and returns its result.  If f throws an SQLException,
  logs the exception and its parent and then calls (System/exit 1)"
  ;; Here we've preserved existing behavior, but this may warrant
  ;; further consideration later.  If possible, we might want to
  ;; avoid System/exit in favor of careful exception
  ;; handling (cf. PDB-1118).
  (try
    (f)
    (catch java.sql.SQLException e
      (log/error e "Caught SQLException during migration")
      (let [next (.getNextException e)]
        (when-not (nil? next)
          (log/error next "Unravelled exception")))
      (binding [*out* *err*] (flush)) (flush)
      (System/exit 1))))

(defn migrate!
  "Migrates database to the latest schema version. Does nothing if
  database is already at the latest schema version.  Requires a
  connection pool because some operations may require an indepdendent
  database connection."
  [db-connection-pool]
  (if-let [unexpected (first (difference (applied-migrations) (kitchensink/keyset migrations)))]
    (throw (IllegalStateException.
            (format "Your PuppetDB database contains a schema migration numbered %d, but this version of PuppetDB does not recognize that version."
                    unexpected))))
  (if-let [pending (seq (pending-migrations))]
    (do
      (jdbc/with-db-transaction []
       (doseq [[version migration] pending]
         (log/infof "Applying database migration version %d" version)
         (sql-or-die (fn [] (migration) (record-migration! version)))))
      (when (sutils/postgres?)
        ;; Make sure all tables (even small static tables) are
        ;; analyzed at least once.  Note that vacuum cannot be
        ;; called from within a transaction block.
        ;; Make sure we're creating a new connection (the new
        ;; clojure.jdbc API will re-use an existing one).
        (assert (not (:connection db-connection-pool)))
        (jdbc/with-db-connection db-connection-pool
          (log/info "Analyzing database")
          (sql-or-die (fn []
                        (-> (doto (:connection (jdbc/db)) (.setAutoCommit true))
                            .createStatement
                            (.execute "vacuum (analyze, verbose)")))))))
    (log/info "There are no pending migrations")))

;; SPECIAL INDEX HANDLING

(defn trgm-indexes!
  "Create trgm indexes if they do not currently exist."
  []
  (when-not (sutils/index-exists? "fact_paths_path_trgm")
    (log/info "Creating additional index `fact_paths_path_trgm`")
    (jdbc/do-commands
     "CREATE INDEX fact_paths_path_trgm ON fact_paths USING gist (path gist_trgm_ops)"))
  (when-not (sutils/index-exists? "fact_values_string_trgm")
    (log/info "Creating additional index `fact_values_string_trgm`")
    (jdbc/do-commands
     "CREATE INDEX fact_values_string_trgm ON fact_values USING gin (value_string gin_trgm_ops)")))

(defn indexes!
  "Create missing indexes for applicable database platforms."
  [config]
  (if (and (sutils/postgres?)
           (sutils/db-version-newer-than? [9 2]))
    (jdbc/with-db-transaction []
     (if (sutils/pg-extension? "pg_trgm")
       (trgm-indexes!)
       (log/warn
        (str
         "Missing PostgreSQL extension `pg_trgm`\n\n"
         "We are unable to create the recommended pg_trgm indexes due to\n"
         "the extension not being installed correctly. Run the command:\n\n"
         "    CREATE EXTENSION pg_trgm;\n\n"
         "as the database super user on the PuppetDB database to correct\n"
         "this, then restart PuppetDB.\n"))))
    (when (conf/foss? config)
      (log/warn
       (str
        "Unable to install optimal indexing\n\n"
        "We are unable to create optimal indexes for your database.\n"
        "For maximum index performance, we recommend using PostgreSQL 9.3 or\n"
        "greater.\n")))))
