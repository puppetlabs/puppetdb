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
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.config :as conf]))

(defn init-through-3-0-0 []
  (jdbc/do-commands

   ;; catalog_resources table
   "CREATE TABLE catalog_resources (
    catalog_id bigint NOT NULL,
    resource bytea NOT NULL,
    tags text[] NOT NULL,
    type text NOT NULL,
    title text NOT NULL,
    exported boolean NOT NULL,
    file text,
    line integer,
    PRIMARY KEY (catalog_id, type, title))"

   ;; catalogs table
   "CREATE SEQUENCE catalogs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    CYCLE"

   "CREATE TABLE catalogs (
    id bigint DEFAULT nextval('catalogs_id_seq'::regclass) NOT NULL PRIMARY KEY,
    hash bytea NOT NULL UNIQUE,
    transaction_uuid uuid,
    certname text NOT NULL UNIQUE,
    producer_timestamp timestamp with time zone NOT NULL,
    api_version integer NOT NULL,
    catalog_version text NOT NULL,
    \"timestamp\" timestamp with time zone,
    environment_id bigint)"

   ;; certnames table

   "CREATE SEQUENCE certname_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    CYCLE"

   "CREATE TABLE certnames (
    id bigint DEFAULT nextval('certname_id_seq'::regclass) NOT NULL,
    certname text NOT NULL,
    latest_report_id bigint,
    deactivated timestamp with time zone,
    expired timestamp with time zone,
    CONSTRAINT certnames_transform_certname_key UNIQUE (certname),
    CONSTRAINT certnames_transform_pkey PRIMARY KEY (id))"

   ;; edges table
   "CREATE TABLE edges (
    certname text NOT NULL,
    source bytea NOT NULL,
    target bytea NOT NULL,
    type text NOT NULL,
    CONSTRAINT edges_certname_source_target_type_unique_key UNIQUE (certname, source, target, type))"

   ;; environments table
   "CREATE SEQUENCE environments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1"

   "CREATE TABLE environments (
    id bigint DEFAULT nextval('environments_id_seq'::regclass) NOT NULL PRIMARY KEY,
    name text NOT NULL UNIQUE)"

   ;; fact_paths table
   "CREATE SEQUENCE fact_paths_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    CYCLE"

   "CREATE TABLE fact_paths (
    id bigint DEFAULT nextval('fact_paths_id_seq'::regclass) NOT NULL PRIMARY KEY,
    depth integer NOT NULL,
    name character varying(1024),
    path text NOT NULL UNIQUE)"

   ;; fact_values table
   "CREATE SEQUENCE fact_values_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    CYCLE"

   "CREATE TABLE fact_values (
    id bigint DEFAULT nextval('fact_values_id_seq'::regclass) NOT NULL PRIMARY KEY,
    value_type_id bigint NOT NULL,
    value_hash bytea NOT NULL UNIQUE,
    value_integer bigint,
    value_float double precision,
    value_string text,
    value_boolean boolean,
    value text)"

   ;; facts table
   "CREATE TABLE facts (
    factset_id bigint NOT NULL,
    fact_path_id bigint NOT NULL,
    fact_value_id bigint NOT NULL,
    CONSTRAINT facts_factset_id_fact_path_id_fact_key UNIQUE (factset_id, fact_path_id))"

   ;; factsets table
   "CREATE SEQUENCE factsets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    CYCLE"

   "CREATE TABLE factsets (
    id bigint DEFAULT nextval('factsets_id_seq'::regclass) NOT NULL PRIMARY KEY,
    certname text NOT NULL UNIQUE,
    \"timestamp\" timestamp with time zone NOT NULL,
    environment_id bigint,
    hash bytea UNIQUE,
    producer_timestamp timestamp with time zone NOT NULL,
    CONSTRAINT factsets_certname_idx UNIQUE(certname))"

   ;; report_statuses table
   "CREATE SEQUENCE report_statuses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1"

   "CREATE TABLE report_statuses (
    id bigint DEFAULT nextval('report_statuses_id_seq'::regclass) NOT NULL PRIMARY KEY,
    status text NOT NULL UNIQUE)"

   "ALTER SEQUENCE report_statuses_id_seq OWNED BY report_statuses.id" ;

   ;; reports table
   "CREATE SEQUENCE reports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
    CYCLE"

   "CREATE TABLE reports (
    id bigint DEFAULT nextval('reports_id_seq'::regclass) NOT NULL PRIMARY KEY,
    hash bytea NOT NULL UNIQUE,
    transaction_uuid uuid,
    certname text NOT NULL,
    puppet_version character varying(255) NOT NULL,
    report_format smallint NOT NULL,
    configuration_version character varying(255) NOT NULL,
    start_time timestamp with time zone NOT NULL,
    end_time timestamp with time zone NOT NULL,
    receive_time timestamp with time zone NOT NULL,
    noop boolean,
    environment_id bigint,
    status_id bigint,
    metrics json,
    logs json,
    producer_timestamp timestamp with time zone NOT NULL)"

   ;; resource_events table
   "CREATE TABLE resource_events (
    report_id bigint NOT NULL,
    certname_id bigint NOT NULL,
    status character varying(40) NOT NULL,
    \"timestamp\" timestamp with time zone NOT NULL,
    resource_type text NOT NULL,
    resource_title text NOT NULL,
    property character varying(40),
    new_value text,
    old_value text,
    message text,
    file character varying(1024) DEFAULT NULL::character varying,
    line integer,
    containment_path text[],
    containing_class character varying(255),
    CONSTRAINT resource_events_unique UNIQUE (report_id, resource_type, resource_title, property))"

   ;; resource_params table
   "CREATE TABLE resource_params (
    resource bytea NOT NULL,
    name text NOT NULL,
    value text NOT NULL,
    CONSTRAINT resource_params_pkey PRIMARY KEY (resource, name))"

   ;; resource_params_cache table
   "CREATE TABLE resource_params_cache (
    resource bytea NOT NULL PRIMARY KEY,
    parameters text)"

   ;; schema_migrations table
   "CREATE TABLE schema_migrations (
    version integer NOT NULL PRIMARY KEY,
    \"time\" timestamp without time zone NOT NULL)"

   ;; value_types table
   "CREATE TABLE value_types (
    id bigint NOT NULL PRIMARY KEY,
    type character varying(32))"

   ;; indexes
   "CREATE INDEX fact_paths_name ON fact_paths USING btree (name)"

   "CREATE INDEX fact_values_value_float_idx ON fact_values USING btree (value_float)"
   "CREATE INDEX fact_values_value_integer_idx ON fact_values USING btree (value_integer)"

   "CREATE INDEX facts_fact_path_id_idx ON facts USING btree (fact_path_id)"
   "CREATE INDEX facts_fact_value_id_idx ON facts USING btree (fact_value_id)"

   "CREATE INDEX idx_catalog_resources_exported_true ON catalog_resources USING btree (exported) WHERE (exported = true)"
   "CREATE INDEX idx_catalog_resources_resource ON catalog_resources USING btree (resource)"
   "CREATE INDEX idx_catalog_resources_type ON catalog_resources USING btree (type)"
   "CREATE INDEX idx_catalog_resources_type_title ON catalog_resources USING btree (type, title)"

   "CREATE INDEX idx_catalogs_env ON catalogs USING btree (environment_id)"
   "CREATE INDEX idx_catalogs_producer_timestamp ON catalogs USING btree (producer_timestamp)"
   "CREATE INDEX idx_catalogs_transaction_uuid ON catalogs USING btree (transaction_uuid)"

   "CREATE INDEX reports_certname_idx ON reports USING btree (certname)"
   "CREATE INDEX reports_end_time_idx ON reports USING btree (end_time)"
   "CREATE INDEX reports_environment_id_idx ON reports USING btree (environment_id)"
   "CREATE INDEX reports_status_id_idx ON reports USING btree (status_id)"
   "CREATE INDEX reports_transaction_uuid_idx ON reports USING btree (transaction_uuid)"
   "CREATE INDEX idx_reports_producer_timestamp ON reports USING btree (producer_timestamp)"

   "CREATE INDEX resource_events_containing_class_idx ON resource_events USING btree (containing_class)"
   "CREATE INDEX resource_events_property_idx ON resource_events USING btree (property)"
   "CREATE INDEX resource_events_reports_id_idx ON resource_events USING btree (report_id)"
   "CREATE INDEX resource_events_resource_type_idx ON resource_events USING btree (resource_type)"
   "CREATE INDEX resource_events_status_idx ON resource_events USING btree (status)"
   "CREATE INDEX resource_events_timestamp_idx ON resource_events USING btree (\"timestamp\")"
   "CREATE INDEX resource_events_resource_title_idx ON resource_events USING btree (resource_title);"
   "CREATE INDEX resource_events_resource_timestamp ON resource_events USING btree (resource_type, resource_title, \"timestamp\")";

   "CREATE INDEX idx_resources_params_name ON resource_params USING btree (name)"
   "CREATE INDEX idx_resources_params_resource ON resource_params USING btree (resource)"

   ;; foreign key updates
   "ALTER TABLE ONLY catalog_resources
    ADD CONSTRAINT catalog_resources_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES catalogs(id) ON DELETE CASCADE,
    ADD CONSTRAINT catalog_resources_resource_fkey FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE"

   "ALTER TABLE ONLY catalogs
    ADD CONSTRAINT catalogs_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(certname) ON DELETE CASCADE,
    ADD CONSTRAINT catalogs_env_fkey FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE"

   "ALTER TABLE ONLY certnames
    ADD CONSTRAINT certnames_reports_id_fkey FOREIGN KEY (latest_report_id) REFERENCES reports(id) ON DELETE SET NULL"

   "ALTER TABLE ONLY edges
    ADD CONSTRAINT edges_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(certname) ON DELETE CASCADE"

   "ALTER TABLE ONLY facts
    ADD CONSTRAINT fact_path_id_fk FOREIGN KEY (fact_path_id) REFERENCES fact_paths(id),
    ADD CONSTRAINT fact_value_id_fk FOREIGN KEY (fact_value_id) REFERENCES fact_values(id)"

   "ALTER TABLE ONLY fact_values
    ADD CONSTRAINT fact_values_value_type_id_fk FOREIGN KEY (value_type_id) REFERENCES value_types(id) ON UPDATE RESTRICT ON DELETE RESTRICT"

   "ALTER TABLE ONLY facts
    ADD CONSTRAINT factset_id_fk FOREIGN KEY (factset_id) REFERENCES factsets(id) ON UPDATE CASCADE ON DELETE CASCADE"

   "ALTER TABLE ONLY factsets
    ADD CONSTRAINT factsets_certname_fk FOREIGN KEY (certname) REFERENCES certnames(certname) ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT factsets_environment_id_fk FOREIGN KEY (environment_id) REFERENCES environments(id) ON UPDATE RESTRICT ON DELETE RESTRICT"

   "ALTER TABLE ONLY reports
    ADD CONSTRAINT reports_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(certname) ON DELETE CASCADE,
    ADD CONSTRAINT reports_env_fkey FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE,
    ADD CONSTRAINT reports_status_fkey FOREIGN KEY (status_id) REFERENCES report_statuses(id) ON DELETE CASCADE"

   "ALTER TABLE ONLY resource_events
    ADD CONSTRAINT resource_events_report_id_fkey FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE"

   "ALTER TABLE ONLY resource_params
    ADD CONSTRAINT resource_params_resource_fkey FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE"

   ;; Load value_types data
   "INSERT INTO value_types (id, type) values (0, 'string')"
   "INSERT INTO value_types (id, type) values (1, 'integer')"
   "INSERT INTO value_types (id, type) values (2, 'float')"
   "INSERT INTO value_types (id, type) values (3, 'boolean')"
   "INSERT INTO value_types (id, type) values (4, 'null')"
   "INSERT INTO value_types (id, type) values (5, 'json')"))

(defn add-code-id-to-catalogs []
  (jdbc/do-commands "ALTER TABLE catalogs ADD code_id TEXT"))

(defn rename-environments-name-to-environment
  []
  (jdbc/do-commands
   "ALTER TABLE environments RENAME COLUMN name TO environment"))

(defn add-jsonb-columns-for-metrics-and-logs
  []
  (jdbc/do-commands
   "ALTER TABLE reports RENAME COLUMN metrics TO metrics_json"
   "ALTER TABLE reports RENAME COLUMN logs TO logs_json"
   "ALTER TABLE reports ADD COLUMN metrics jsonb DEFAULT NULL"
   "ALTER TABLE reports ADD COLUMN logs jsonb DEFAULT NULL"
   "ALTER TABLE reports ADD COLUMN resources jsonb DEFAULT NULL"))

(defn factset-hash-field-not-nullable
  []
  (jdbc/do-commands
   "UPDATE factsets SET hash=md5(factsets.id::text)::bytea WHERE hash is NULL"
   "ALTER TABLE factsets ALTER COLUMN hash SET NOT NULL"))

(def migrations
  "The available migrations, as a map from migration version to migration function."
  {34 init-through-3-0-0
   ;; This dummy migration ensures that even databases that were up to
   ;; date when the "vacuum analyze" code was added to migrate! will
   ;; still analyze their existing databases.
   35 (fn [] true)
   36 rename-environments-name-to-environment
   37 add-jsonb-columns-for-metrics-and-logs
   38 add-code-id-to-catalogs
   39 factset-hash-field-not-nullable
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
      (let [message (.getMessage e)]
        (if (or (re-find #"object not found: SCHEMA_MIGRATIONS" message)
                (re-find #"\"schema_migrations\" does not exist" message))
          (sorted-set)
          (throw e))))))

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

(defn previous-migrations
  "Returns the list of migration numbers that existed before the
  current known set. These migrations can't be upgraded from, but are
  recognized and shouldn't cause errors if they are present"
  [known-migrations]
  (range 1 (first known-migrations)))

(defn unrecognized-migrations
  "Returns a set of migrations, likely created by a future version of
  PuppetDB"
  [applied-migrations known-migrations]
  (->> known-migrations
       previous-migrations
       (into known-migrations)
       (difference applied-migrations)))

(defn migrate!
  "Migrates database to the latest schema version. Does nothing if
  database is already at the latest schema version.  Requires a
  connection pool because some operations may require an indepdendent
  database connection."
  [db-connection-pool]
  (let [applied-migration-versions (applied-migrations)
        latest-applied-migration (last applied-migration-versions)
        known-migrations (apply sorted-set (keys migrations))]

    (when (and latest-applied-migration
               (< latest-applied-migration (first known-migrations)))
      (throw (IllegalStateException.
              (format (str "Found an old and unuspported database migration (migration number %s)."
                           " PuppetDB only supports upgrading from the previous major version to the current major version."
                           " As an example, users wanting to upgrade from 2.x to 4.x should first upgrade to 3.x.")
                      latest-applied-migration))))

    (when-let [unexpected (first (unrecognized-migrations applied-migration-versions known-migrations))]
      (throw (IllegalStateException.
              (format "Your PuppetDB database contains a schema migration numbered %d, but this version of PuppetDB does not recognize that version."
                      unexpected))))

    (if-let [pending (seq (pending-migrations))]
      (do
        (jdbc/with-db-transaction []
          (doseq [[version migration] pending]
            (log/infof "Applying database migration version %d" version)
            (sql-or-die (fn [] (migration) (record-migration! version)))))
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
                            (.execute "vacuum (analyze, verbose)"))))))
      (log/info "There are no pending migrations"))))

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
        "this, then restart PuppetDB.\n")))))
