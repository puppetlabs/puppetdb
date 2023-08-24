(ns puppetlabs.puppetdb.scf.migrate
  "Schema migrations

   The `initialize-schema` function can be used to prepare the
  database, applying all the pending migrations to the database, in
  ascending order of schema version. Pending is defined as having a
  schema version greater than the current version in the database.

   A migration is specified by defining a function of arity 0 and adding it to
   the `migrations` map, along with its schema version. To apply the migration,
   the migration function will be invoked, and the schema version and current
   time will be recorded in the schema_migrations table.

   A migration function can return a map with ::vacuum-analyze to indicate what tables
   need to be analyzed post-migration.

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
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [murphy :refer [try!]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.time :refer [in-millis interval now to-timestamp]]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.structured-logging.core :refer [maplog]]
            [puppetlabs.puppetdb.scf.partitioning :as partitioning
             :refer [get-temporal-partitions]]
            [schema.core :as s])
  (:import
   (java.time LocalDateTime LocalDate ZonedDateTime Instant)
   (java.time.temporal ChronoUnit)
   (java.time.format DateTimeFormatter)
   (org.postgresql.util PGobject)))

(defn init-through-2-3-8
  []

  (jdbc/do-commands

    ;; catalog_resources table
    "CREATE TABLE catalog_resources (
        catalog_id bigint NOT NULL,
        resource character varying(40) NOT NULL,
        type text NOT NULL,
        title text NOT NULL,
        tags text[] NOT NULL,
        exported boolean NOT NULL,
        file text,
        line integer)"

    ;; catalogs table
    "CREATE TABLE catalogs (
        id bigint NOT NULL,
        hash character varying(40) NOT NULL,
        api_version integer,
        catalog_version text NOT NULL,
        transaction_uuid character varying(255) DEFAULT NULL::character varying,
        \"timestamp\" timestamp with time zone,
        certname text NOT NULL,
        environment_id integer,
        producer_timestamp timestamp with time zone)"

    "CREATE SEQUENCE catalogs_transform_id_seq1
        START WITH 1
        INCREMENT BY 1
        NO MINVALUE
        NO MAXVALUE
        CACHE 1"

    ;; certnames table
    "CREATE TABLE certnames (
        name text NOT NULL,
        deactivated timestamp with time zone)"

    ;; edges table
    "CREATE TABLE edges (
        certname text NOT NULL,
        source character varying(40) NOT NULL,
        target character varying(40) NOT NULL,
        type text NOT NULL)"

    ;; environments table
    "CREATE TABLE environments (
        id bigint NOT NULL,
        name text NOT NULL)"

    "CREATE SEQUENCE environments_id_seq
        START WITH 1
        INCREMENT BY 1
        NO MINVALUE
        NO MAXVALUE
        CACHE 1"

    "CREATE SEQUENCE fact_paths_id_seq
        START WITH 1
        INCREMENT BY 1
        NO MINVALUE
        NO MAXVALUE
        CACHE 1
        CYCLE"

    ;; fact_paths table
    "CREATE TABLE fact_paths (
        id bigint DEFAULT nextval('fact_paths_id_seq'::regclass) NOT NULL,
        depth integer NOT NULL,
        name character varying(1024),
        path text NOT NULL)"

    "CREATE SEQUENCE fact_values_id_seq
        START WITH 1
        INCREMENT BY 1
        NO MINVALUE
        NO MAXVALUE
        CACHE 1
        CYCLE"

    ;; fact_values table
    "CREATE TABLE fact_values (
        id bigint DEFAULT nextval('fact_values_id_seq'::regclass) NOT NULL,
        value_type_id bigint NOT NULL,
        value_hash character varying(40) NOT NULL,
        value_integer bigint,
        value_float double precision,
        value_string text,
        value_boolean boolean,
        value_json text)"

    ;; facts table
    "CREATE TABLE facts (
        factset_id bigint NOT NULL,
        fact_path_id bigint NOT NULL,
        fact_value_id bigint NOT NULL)"

    "CREATE SEQUENCE factsets_id_seq
        START WITH 1
        INCREMENT BY 1
        NO MINVALUE
        NO MAXVALUE
        CACHE 1
        CYCLE"

;; factsets table
    "CREATE TABLE factsets (
        id bigint DEFAULT nextval('factsets_id_seq'::regclass) NOT NULL,
        certname text NOT NULL,
        \"timestamp\" timestamp with time zone NOT NULL,
        environment_id bigint,
        producer_timestamp timestamp with time zone)"

    ;; latest_reports table
    "CREATE TABLE latest_reports (
        certname text NOT NULL,
        report character varying(40) NOT NULL)"

    ;; report_statuses table
    "CREATE TABLE report_statuses (
        id bigint NOT NULL,
        status text NOT NULL)"

    "CREATE SEQUENCE report_statuses_id_seq
        START WITH 1
        INCREMENT BY 1
        NO MINVALUE
        NO MAXVALUE
        CACHE 1"

    ;; reports table
    "CREATE TABLE reports (
        hash character varying(40) NOT NULL,
        certname text,
        puppet_version character varying(255) NOT NULL,
        report_format smallint NOT NULL,
        configuration_version character varying(255) NOT NULL,
        start_time timestamp with time zone NOT NULL,
        end_time timestamp with time zone NOT NULL,
        receive_time timestamp with time zone NOT NULL,
        transaction_uuid character varying(255) DEFAULT NULL::character varying,
        environment_id integer,
        status_id integer)"

    ;; resource_events table
    "CREATE TABLE resource_events (
        report character varying(40) NOT NULL,
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
        containing_class character varying(255))"

    ;; resource_params table
    "CREATE TABLE resource_params (
        resource character varying(40) NOT NULL,
        name text NOT NULL,
        value text NOT NULL)"

    ;; resource_params_cache table
    "CREATE TABLE resource_params_cache (
        resource character varying(40) NOT NULL,
        parameters text)"

    ;; value_types table
    "CREATE TABLE value_types (
        id bigint NOT NULL,
        type character varying(32))"

    ;; Load value_types data
    "INSERT INTO value_types (id, type) values (0, 'string')"
    "INSERT INTO value_types (id, type) values (1, 'integer')"
    "INSERT INTO value_types (id, type) values (2, 'float')"
    "INSERT INTO value_types (id, type) values (3, 'boolean')"
    "INSERT INTO value_types (id, type) values (4, 'null')"
    "INSERT INTO value_types (id, type) values (5, 'json')"

    ;; constraints

    "ALTER TABLE ONLY catalogs ALTER COLUMN id SET DEFAULT nextval('catalogs_transform_id_seq1'::regclass)"

    "ALTER TABLE ONLY environments ALTER COLUMN id SET DEFAULT nextval('environments_id_seq'::regclass)"

    "ALTER TABLE ONLY report_statuses ALTER COLUMN id SET DEFAULT nextval('report_statuses_id_seq'::regclass)"

    "ALTER TABLE ONLY catalog_resources
        ADD CONSTRAINT catalog_resources_pkey PRIMARY KEY (catalog_id, type, title)"

    "ALTER TABLE ONLY catalogs
        ADD CONSTRAINT catalogs_certname_key UNIQUE (certname)"

    "ALTER TABLE ONLY catalogs
        ADD CONSTRAINT catalogs_hash_key UNIQUE (hash)"

    "ALTER TABLE ONLY catalogs
        ADD CONSTRAINT catalogs_pkey PRIMARY KEY (id)"

    "ALTER TABLE ONLY certnames
        ADD CONSTRAINT certnames_pkey PRIMARY KEY (name)"

    "ALTER TABLE ONLY resource_events
        ADD CONSTRAINT constraint_resource_events_unique UNIQUE (report, resource_type, resource_title, property)"

    "ALTER TABLE ONLY edges
        ADD CONSTRAINT edges_certname_source_target_type_unique_key UNIQUE (certname, source, target, type)"

    "ALTER TABLE ONLY environments
        ADD CONSTRAINT environments_name_key UNIQUE (name)"

    "ALTER TABLE ONLY environments
        ADD CONSTRAINT environments_pkey PRIMARY KEY (id)"

    "ALTER TABLE ONLY fact_paths
        ADD CONSTRAINT fact_paths_path_key UNIQUE (path)"

    "ALTER TABLE ONLY fact_paths
        ADD CONSTRAINT fact_paths_pkey PRIMARY KEY (id)"

    "ALTER TABLE ONLY fact_values
        ADD CONSTRAINT fact_values_pkey PRIMARY KEY (id)"

    "ALTER TABLE ONLY fact_values
        ADD CONSTRAINT fact_values_value_hash_key UNIQUE (value_hash)"

    "ALTER TABLE ONLY facts
        ADD CONSTRAINT facts_factset_id_fact_path_id_fact_key UNIQUE (factset_id, fact_path_id)"

    "ALTER TABLE ONLY factsets
        ADD CONSTRAINT factsets_certname_idx UNIQUE (certname)"

    "ALTER TABLE ONLY factsets
        ADD CONSTRAINT factsets_pkey PRIMARY KEY (id)"

    "ALTER TABLE ONLY latest_reports
        ADD CONSTRAINT latest_reports_pkey PRIMARY KEY (certname)"

    "ALTER TABLE ONLY report_statuses
        ADD CONSTRAINT report_statuses_pkey PRIMARY KEY (id)"

    "ALTER TABLE ONLY report_statuses
        ADD CONSTRAINT report_statuses_status_key UNIQUE (status)"

    "ALTER TABLE ONLY reports
        ADD CONSTRAINT reports_pkey PRIMARY KEY (hash)"

    "ALTER TABLE ONLY resource_params_cache
        ADD CONSTRAINT resource_params_cache_pkey PRIMARY KEY (resource)"

    "ALTER TABLE ONLY resource_params
        ADD CONSTRAINT resource_params_pkey PRIMARY KEY (resource, name)"

    "ALTER TABLE ONLY value_types
        ADD CONSTRAINT value_types_pkey PRIMARY KEY (id)"

    ;; indexes
    "CREATE INDEX fact_paths_name ON fact_paths USING btree (name)"

    "CREATE INDEX fact_values_value_float_idx ON fact_values USING btree (value_float)"

    "CREATE INDEX fact_values_value_integer_idx ON fact_values USING btree (value_integer)"

    "CREATE INDEX facts_fact_path_id_idx ON facts USING btree (fact_path_id)"

    "CREATE INDEX facts_fact_value_id_idx ON facts USING btree (fact_value_id)"

    "CREATE INDEX idx_catalog_resources_exported_true ON catalog_resources USING btree (exported) WHERE (exported = true)"

    "CREATE INDEX idx_catalog_resources_resource ON catalog_resources USING btree (resource)"

    "CREATE INDEX idx_catalog_resources_type ON catalog_resources USING btree (type)"

    "CREATE INDEX idx_catalog_resources_type_title ON catalog_resources USING btree (type)"

    "CREATE INDEX idx_catalogs_env ON catalogs USING btree (environment_id)"

    "CREATE INDEX idx_catalogs_producer_timestamp ON catalogs USING btree (producer_timestamp)"

    "CREATE INDEX idx_catalogs_transaction_uuid ON catalogs USING btree (transaction_uuid)"

    "CREATE INDEX idx_latest_reports_report ON latest_reports USING btree (report)"

    "CREATE INDEX idx_reports_certname ON reports USING btree (certname)"

    "CREATE INDEX idx_reports_end_time ON reports USING btree (end_time)"

    "CREATE INDEX idx_reports_env ON reports USING btree (environment_id)"

    "CREATE INDEX idx_reports_status ON reports USING btree (status_id)"

    "CREATE INDEX idx_reports_transaction_uuid ON reports USING btree (transaction_uuid)"

    "CREATE INDEX idx_resource_events_containing_class ON resource_events USING btree (containing_class)"

    "CREATE INDEX idx_resource_events_property ON resource_events USING btree (property)"

    "CREATE INDEX idx_resource_events_report ON resource_events USING btree (report)"

    "CREATE INDEX idx_resource_events_resource_type ON resource_events USING btree (resource_type)"

    "CREATE INDEX idx_resource_events_resource_type_title ON resource_events USING btree (resource_type, resource_title)"

    "CREATE INDEX idx_resource_events_status ON resource_events USING btree (status)"

    "CREATE INDEX idx_resource_events_timestamp ON resource_events USING btree (\"timestamp\")"

    "CREATE INDEX idx_resources_params_name ON resource_params USING btree (name)"

    "CREATE INDEX idx_resources_params_resource ON resource_params USING btree (resource)"

    "ALTER TABLE ONLY catalog_resources
        ADD CONSTRAINT catalog_resources_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES catalogs(id) ON DELETE CASCADE"

    "ALTER TABLE ONLY catalog_resources
        ADD CONSTRAINT catalog_resources_resource_fkey FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE"

    "ALTER TABLE ONLY catalogs
        ADD CONSTRAINT catalogs_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(name) ON DELETE CASCADE"

    "ALTER TABLE ONLY catalogs
        ADD CONSTRAINT catalogs_env_fkey FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE"

    "ALTER TABLE ONLY edges
        ADD CONSTRAINT edges_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(name) ON DELETE CASCADE"

    "ALTER TABLE ONLY facts
        ADD CONSTRAINT fact_path_id_fk FOREIGN KEY (fact_path_id) REFERENCES fact_paths(id)"

    "ALTER TABLE ONLY facts
        ADD CONSTRAINT fact_value_id_fk FOREIGN KEY (fact_value_id) REFERENCES fact_values(id)"

    "ALTER TABLE ONLY fact_values
        ADD CONSTRAINT fact_values_value_type_id_fk FOREIGN KEY (value_type_id) REFERENCES value_types(id) ON UPDATE RESTRICT ON DELETE RESTRICT"

    "ALTER TABLE ONLY facts
        ADD CONSTRAINT factset_id_fk FOREIGN KEY (factset_id) REFERENCES factsets(id) ON UPDATE CASCADE ON DELETE CASCADE"

    "ALTER TABLE ONLY factsets
        ADD CONSTRAINT factsets_certname_fk FOREIGN KEY (certname) REFERENCES certnames(name) ON UPDATE CASCADE ON DELETE CASCADE"

    "ALTER TABLE ONLY factsets
        ADD CONSTRAINT factsets_environment_id_fk FOREIGN KEY (environment_id) REFERENCES environments(id) ON UPDATE RESTRICT ON DELETE RESTRICT"

    "ALTER TABLE ONLY latest_reports
        ADD CONSTRAINT latest_reports_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(name) ON DELETE CASCADE"

    "ALTER TABLE ONLY latest_reports
        ADD CONSTRAINT latest_reports_report_fkey FOREIGN KEY (report) REFERENCES reports(hash) ON DELETE CASCADE"

    "ALTER TABLE ONLY reports
        ADD CONSTRAINT reports_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(name) ON DELETE CASCADE"

    "ALTER TABLE ONLY reports
        ADD CONSTRAINT reports_env_fkey FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE"

    "ALTER TABLE ONLY reports
        ADD CONSTRAINT reports_status_fkey FOREIGN KEY (status_id) REFERENCES report_statuses(id) ON DELETE CASCADE"

    "ALTER TABLE ONLY resource_events
        ADD CONSTRAINT resource_events_report_fkey FOREIGN KEY (report) REFERENCES reports(hash) ON DELETE CASCADE"

    "ALTER TABLE ONLY resource_params
        ADD CONSTRAINT resource_params_resource_fkey FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE"))

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
  (let [hash-type "bytea"
        uuid-type "uuid"
        json-type "json"
        munge-hash (fn [column] (format "('\\x' || %s)::bytea" column))
        munge-uuid (fn [column] (format "%s::uuid" column))]

    (jdbc/do-commands
      "UPDATE catalogs SET producer_timestamp=timestamp
         WHERE producer_timestamp IS NULL"
      "UPDATE factsets SET producer_timestamp=timestamp
         WHERE producer_timestamp IS NULL"

      (sql/create-table-ddl
       :factsets_transform
       [["id" "bigint NOT NULL DEFAULT nextval('factsets_id_seq')"]
        ["certname" "text NOT NULL"]
        ["timestamp" "timestamp with time zone NOT NULL"]
        ["environment_id" "bigint"]
        ["hash" hash-type]
        ["producer_timestamp" "timestamp with time zone NOT NULL"]])

      "INSERT INTO factsets_transform
         (id, certname, timestamp, environment_id, producer_timestamp)
         SELECT id, certname, timestamp, environment_id, timestamp
           FROM factsets fs"

      (sql/create-table-ddl
       :fact_values_transform
       [["id" "bigint NOT NULL DEFAULT nextval('fact_values_id_seq')"]
        ["value_hash" hash-type "NOT NULL"]
        ["value_type_id" "bigint NOT NULL"]
        ["value_integer" "bigint"]
        ["value_float" "double precision"]
        ["value_string" "text"]
        ["value_boolean" "boolean"]
        ["value_json" "text"]])

      (str "INSERT INTO fact_values_transform
              (id, value_hash, value_type_id, value_integer, value_float,
               value_string, value_boolean, value_json)
              SELECT id, " (munge-hash "value_hash") ", value_type_id,
                     value_integer, value_float, value_string, value_boolean,
                     value_json
                FROM fact_values")

      (sql/create-table-ddl :resource_params_cache_transform
                            [["resource" hash-type "NOT NULL"]
                             ["parameters" "TEXT"]])

      (str "INSERT INTO resource_params_cache_transform
              (resource, parameters)
              SELECT " (munge-hash "resource") ", parameters
                FROM resource_params_cache")

      (sql/create-table-ddl
       :catalog_resources_transform
       [["catalog_id" "bigint NOT NULL"]
        ["resource" hash-type "NOT NULL"]
        ["tags" (sutils/sql-array-type-string "TEXT") "NOT NULL"]
        ["type" "TEXT" "NOT NULL"]
        ["title" "TEXT" "NOT NULL"]
        ["exported" "BOOLEAN" "NOT NULL"]
        ["file" "TEXT"]
        ["line" "INT"]])

      (str "INSERT INTO catalog_resources_transform
              (resource, catalog_id, tags, type, title, exported, file, line)
              SELECT " (munge-hash "resource") ", catalog_id, tags, type, title,
                       exported, file, line
                FROM catalog_resources")

      (sql/create-table-ddl :resource_params_transform
                            [["resource" hash-type "NOT NULL"]
                             ["name"  "TEXT" "NOT NULL"]
                             ["value" "TEXT" "NOT NULL"]])

      (str "INSERT INTO resource_params_transform
              (resource, name, value)
              SELECT " (munge-hash "resource") ", name, value
                FROM resource_params")

      (sql/create-table-ddl :edges_transform
                            [["certname" "TEXT" "NOT NULL"]
                             ["source" hash-type "NOT NULL"]
                             ["target" hash-type "NOT NULL"]
                             ["type" "TEXT" "NOT NULL"]])

      (str "INSERT INTO edges_transform (certname, source, target, type)
              SELECT certname, " (munge-hash "source") ",
                   " (munge-hash "target") ", type
              FROM edges")

      "CREATE SEQUENCE catalogs_id_seq CYCLE"

      (sql/create-table-ddl
       :catalogs_transform
       [["id" "bigint NOT NULL DEFAULT nextval('catalogs_id_seq')"]
        ["hash" hash-type "NOT NULL"]
        ["transaction_uuid" uuid-type]
        ["certname" "text NOT NULL"]
        ["producer_timestamp" "timestamp with time zone NOT NULL"]
        ["api_version" "INTEGER NOT NULL"]
        ["timestamp" "TIMESTAMP WITH TIME ZONE"]
        ["catalog_version" "TEXT NOT NULL"]
        ["environment_id" "bigint"]])

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
       [["id" "bigint NOT NULL DEFAULT nextval('reports_id_seq')"]
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
        ["logs" json-type]])

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
       [["report_id" "bigint NOT NULL"]
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
        ["containing_class" "varchar(255)"]])

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
       [["certname" "text NOT NULL"]
        ["latest_report_id" "bigint"]
        ["deactivated" "timestamp with time zone"]])

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

      "CREATE INDEX idx_catalog_resources_exported_true
         ON catalog_resources (exported) WHERE exported = true"
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

      "ALTER TABLE certnames ADD CONSTRAINT certnames_reports_id_fkey
         FOREIGN KEY (latest_report_id) REFERENCES reports(id)
         ON DELETE SET NULL")))

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
  [{:keys [id value]}]
  (jdbc/update! :fact_values
                {:value_json (json/generate-string value)}
                ["id=?" id]))

(defn coalesce-fact-values
  []
  (let [query ["select * from fact_values"]
        value-keys [:value_string :value_integer
                    :value_json :value_boolean
                    :value_float]]
    (jdbc/call-with-array-converted-query-rows
     query
     (fn [rows]
       (->> rows
            (map (partial coalesce-values value-keys))
            (map update-value-json)
            dorun)))
    (jdbc/do-commands
      "ALTER TABLE fact_values RENAME COLUMN value_json TO value")))

(defn add-producer-timestamp-to-reports []
  (jdbc/do-commands
   "ALTER TABLE reports ADD producer_timestamp TIMESTAMP WITH TIME ZONE"
   "UPDATE reports SET producer_timestamp=end_time"
   "ALTER TABLE reports ALTER COLUMN producer_timestamp SET NOT NULL"
   "CREATE INDEX idx_reports_producer_timestamp
      ON reports(producer_timestamp)"))

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
  (jdbc/call-with-query-rows
   ["select id from factsets where hash is null"]
   (fn [rows]
     (doseq [batch (partition-all 500 rows)
             row batch]
       (jdbc/do-prepared "update factsets set hash=? where id=?"
                         [(-> (:id row)
                              .toString
                              kitchensink/utf8-string->sha1
                              sutils/munge-hash-for-storage)
                          (:id row)]))))

  (jdbc/do-commands
   "ALTER TABLE factsets ALTER COLUMN hash SET NOT NULL"))

(defn add-expression-indexes-for-bytea-queries
  []
  (jdbc/do-commands
    "CREATE UNIQUE INDEX reports_hash_expr_idx ON reports(trim(leading '\\x' from hash::text))"
    "ALTER TABLE REPORTS DROP CONSTRAINT reports_hash_key"
    "DROP INDEX reports_transaction_uuid_idx"
    "CREATE INDEX reports_tx_uuid_expr_idx ON reports(CAST(transaction_uuid AS text))"

    ;; leave the existing resources/rpc indices for resources-query join
    "CREATE INDEX resources_hash_expr_idx ON catalog_resources(trim(leading '\\x' from resource::text))"
    "CREATE INDEX rpc_hash_expr_idx ON resource_params_cache(trim(leading '\\x' from resource::text))"
    "CREATE INDEX resource_params_hash_expr_idx ON resource_params(trim(leading '\\x' from resource::text))"

    "CREATE UNIQUE INDEX catalogs_hash_expr_idx ON catalogs(trim(leading '\\x' from hash::text))"
    "ALTER TABLE catalogs DROP CONSTRAINT catalogs_hash_key"
    "DROP INDEX idx_catalogs_transaction_uuid"
    "CREATE INDEX catalogs_tx_uuid_expr_idx ON catalogs(CAST(transaction_uuid AS text))"

    "CREATE UNIQUE INDEX factsets_hash_expr_idx ON factsets(trim(leading '\\x' from hash::text))"
    "ALTER TABLE factsets DROP CONSTRAINT factsets_hash_key"))

(defn add-support-for-historical-catalogs []
  ;; Completely retired/obsolete
  (jdbc/do-commands
   "ALTER TABLE catalog_resources RENAME TO catalog_resources_tmp"
   ;; CREATE certnames and catalog_resources transform tables
   "CREATE TABLE catalog_resources (LIKE catalog_resources_tmp INCLUDING ALL)"
   "CREATE TABLE latest_catalogs (catalog_id BIGINT NOT NULL UNIQUE REFERENCES catalogs(id) ON DELETE CASCADE, certname_id BIGINT PRIMARY KEY REFERENCES certnames(id) ON DELETE CASCADE)"
   "ALTER TABLE catalog_resources DROP COLUMN catalog_id"
   "ALTER TABLE catalog_resources ADD COLUMN certname_id BIGINT NOT NULL REFERENCES certnames(id) ON DELETE CASCADE"
   "ALTER TABLE catalog_resources ADD PRIMARY KEY (certname_id, type, title)"

   (str "INSERT INTO latest_catalogs"
        "  (catalog_id, certname_id)"
        "  SELECT catalogs.id, certnames.id"
        "  FROM certnames"
        "  INNER JOIN catalogs ON catalogs.certname = certnames.certname")

   (str "INSERT INTO catalog_resources"
        "  (certname_id, resource, tags, type, title, exported, file, line)"
        "  SELECT latest_catalogs.certname_id, cr.resource, cr.tags, cr.type, cr.title, cr.exported, cr.file, cr.line"
        "  FROM catalog_resources_tmp cr"
        "  INNER JOIN latest_catalogs ON cr.catalog_id = latest_catalogs.catalog_id")

   "DROP TABLE catalog_resources_tmp"

   "ALTER TABLE catalog_resources ADD CONSTRAINT catalog_resources_resource_fkey FOREIGN KEY (resource) REFERENCES resource_params_cache(resource) ON DELETE CASCADE"

   "ALTER TABLE catalogs DROP CONSTRAINT catalogs_certname_key"
   "DROP INDEX catalogs_hash_expr_idx"
   "ALTER TABLE catalogs ADD COLUMN edges JSONB DEFAULT NULL"
   "ALTER TABLE catalogs ADD COLUMN resources JSONB DEFAULT NULL"
   "ALTER TABLE catalogs ADD COLUMN catalog_uuid UUID DEFAULT NULL"
   "ALTER TABLE reports ADD COLUMN catalog_uuid UUID DEFAULT NULL"

   "CREATE INDEX reports_catalog_uuid_idx ON reports (catalog_uuid)"
   "CREATE INDEX catalogs_hash_expr_idx ON catalogs(encode(hash::bytea, 'hex'))"
   "CREATE INDEX catalogs_certname_idx ON catalogs (certname)"))

(defn add-indexes-for-reports-summary-query
  "This aggregate and function is used by PE, not puppetdb"
  []
  (jdbc/do-commands
   "CREATE FUNCTION dual_sha1(BYTEA, BYTEA) RETURNS bytea AS $$
      BEGIN
        RETURN digest($1 || $2, 'sha1');
      END;
    $$ LANGUAGE plpgsql"
   "CREATE AGGREGATE sha1_agg (BYTEA)
    (
      sfunc = dual_sha1,
      stype = bytea,
      initcond = '\\x00'
    )"
   "CREATE INDEX idx_reports_producer_timestamp_by_hour_certname ON reports
    (
      date_trunc('hour', timezone('UTC', producer_timestamp)),
      producer_timestamp,
      certname
    )"))


(defn fix-bytea-expression-indexes-to-use-encode
  []
  (jdbc/do-commands
   "DROP INDEX reports_hash_expr_idx"
   "CREATE UNIQUE INDEX reports_hash_expr_idx ON reports(encode(hash::bytea, 'hex'))"
   "DROP INDEX resources_hash_expr_idx"
   "CREATE INDEX resources_hash_expr_idx ON catalog_resources(encode(resource::bytea, 'hex'))"
   "DROP INDEX rpc_hash_expr_idx"
   "CREATE INDEX rpc_hash_expr_idx ON resource_params_cache(encode(resource::bytea, 'hex'))"
   "DROP INDEX resource_params_hash_expr_idx"
   "CREATE INDEX resource_params_hash_expr_idx ON resource_params(encode(resource::bytea, 'hex'))"
   "DROP INDEX catalogs_hash_expr_idx"
   "CREATE UNIQUE INDEX catalogs_hash_expr_idx ON catalogs(encode(hash::bytea, 'hex'))"
   "DROP INDEX factsets_hash_expr_idx"
   "CREATE UNIQUE INDEX factsets_hash_expr_idx ON factsets(encode(hash::bytea, 'hex'))"))

(defn add-certname-id-to-certnames
  []
  (jdbc/do-commands
   "CREATE SEQUENCE certname_id_seq CYCLE"

   (sql/create-table-ddl
    :certnames_transform
    ;; Rename the 'name' column of certnames to 'certname'.
    [["id" "bigint NOT NULL PRIMARY KEY default nextval('certname_id_seq')"]
     ["certname" "text NOT NULL UNIQUE"]
     ["latest_report_id" "bigint"]
     ["deactivated" "timestamp with time zone"]
     ["expired" "timestamp with time zone"]])

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

   "ALTER TABLE certnames ADD CONSTRAINT certnames_reports_id_fkey
      FOREIGN KEY (latest_report_id)
      REFERENCES reports(id) ON DELETE SET NULL"))

(defn add-certname-id-to-resource-events
  []
  (jdbc/do-commands
   (sql/create-table-ddl
    :resource_events_transform
    [["report_id" "bigint NOT NULL"]
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
     ["containing_class" "varchar(255)"]])

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

(defn add-catalog-uuid-to-reports-and-catalogs
  []
  (jdbc/do-commands
   "ALTER TABLE reports ADD COLUMN cached_catalog_status TEXT"
   "ALTER TABLE reports ADD COLUMN code_id TEXT"
   "UPDATE catalogs SET catalog_uuid=catalogs.transaction_uuid WHERE hash is NULL"
   "UPDATE reports SET catalog_uuid=reports.transaction_uuid WHERE hash is NULL"))

(defn index-certnames-latest-report-id
  []
  (jdbc/do-commands
    "CREATE INDEX idx_certnames_latest_report_id on certnames(latest_report_id)"))

(defn index-certnames-unique-latest-report-id
  []
  (jdbc/do-commands
    "DROP INDEX IF EXISTS idx_certnames_latest_report_id"
    "CREATE UNIQUE INDEX idx_certnames_latest_report_id on certnames(latest_report_id)"))

(defn add-producer-to-reports-catalogs-and-factsets
  []
  (jdbc/do-commands
    (sql/create-table-ddl
      :producers
      [["id" "bigint PRIMARY KEY"]
       ["name" "text NOT NULL UNIQUE"]])
    "CREATE SEQUENCE producers_id_seq CYCLE"
    "ALTER TABLE producers ALTER COLUMN id SET DEFAULT nextval('producers_id_seq')"
    "ALTER TABLE reports ADD COLUMN producer_id bigint"
    "ALTER TABLE factsets ADD COLUMN producer_id bigint"
    "ALTER TABLE catalogs ADD COLUMN producer_id bigint"
    "ALTER TABLE reports
        ADD CONSTRAINT reports_prod_fkey FOREIGN KEY (producer_id) REFERENCES producers(id)"
    "ALTER TABLE factsets
        ADD CONSTRAINT factsets_prod_fk FOREIGN KEY (producer_id) REFERENCES producers(id)"
    "ALTER TABLE catalogs
        ADD CONSTRAINT catalogs_prod_fkey FOREIGN KEY (producer_id) REFERENCES producers(id)"
    "CREATE INDEX idx_reports_prod ON reports(producer_id)"
    "CREATE INDEX idx_factsets_prod ON factsets(producer_id)"
    "CREATE INDEX idx_catalogs_prod ON catalogs(producer_id)"))

(defn drop-certnames-latest-id-index
  []
  (jdbc/do-commands
    "DROP INDEX idx_certnames_latest_report_id"))

(defn add-noop-pending-to-reports
  []
  (jdbc/do-commands
    "ALTER TABLE reports ADD COLUMN noop_pending boolean"
    "CREATE INDEX idx_reports_noop_pending on reports using btree (noop_pending) where (noop_pending = true)"))

(defn add-corrective-change-columns
  []
  (jdbc/do-commands
    "alter table reports add column corrective_change boolean"
    "alter table resource_events add column corrective_change boolean"))

(defn remove-historical-catalogs
  ;; Completely retired/obsolete
  []
  (jdbc/do-commands
    "alter table catalogs drop column edges"
    "alter table catalogs drop column resources"
    "delete from catalogs where id not in (select catalog_id from latest_catalogs)"
    "drop table latest_catalogs"))

(defn migrate-through-app
  [table1 table2 column-list munge-fn]
  (let [columns (str/join "," column-list)]
    (jdbc/call-with-array-converted-query-rows
     [(format "select %s from %s" columns (name table1))]
     #(->> %
           (map munge-fn)
           (jdbc/insert-multi! (name table2))))))

(defn resource-params-cache-parameters-to-jsonb
  []
  (jdbc/do-commands
    (sql/create-table-ddl :resource_params_cache_transform
                          [["resource" "bytea NOT NULL"]
                           ["parameters" "jsonb"]]))

  (migrate-through-app
    :resource_params_cache
    :resource_params_cache_transform
    ["encode(resource::bytea, 'hex') as resource" "parameters"]
    #(-> %
         (update :parameters (comp sutils/munge-jsonb-for-storage json/parse-string))
         (update :resource sutils/munge-hash-for-storage)))

  (jdbc/do-commands
    "alter table catalog_resources drop constraint catalog_resources_resource_fkey"
    "alter table resource_params drop constraint resource_params_resource_fkey"
    "drop table resource_params_cache"

    "alter table resource_params_cache_transform rename to resource_params_cache"

    "alter table resource_params_cache add constraint resource_params_cache_pkey
     primary key (resource)"
    "alter table catalog_resources add constraint catalog_resources_resource_fkey
     foreign key (resource) references resource_params_cache(resource) on delete cascade"
    "alter table resource_params add constraint resource_params_resource_fkey
     foreign key (resource) references resource_params_cache(resource) on delete cascade"
    "create index rpc_hash_expr_idx on resource_params_cache(encode(resource, 'hex'))")   )

(defn fact-values-value-to-jsonb
  []
  (jdbc/do-commands
    (sql/create-table-ddl :fact_values_transform
                          [["id" "bigint NOT NULL PRIMARY KEY DEFAULT nextval('fact_values_id_seq')"]
                           ["value_hash" "bytea NOT NULL UNIQUE"]
                           ["value_type_id" "bigint NOT NULL"]
                           ["value_integer" "bigint"]
                           ["value_float" "double precision"]
                           ["value_string" "text"]
                           ["value_boolean" "boolean"]
                           ["value" "jsonb"]]))

  (migrate-through-app
    :fact_values
    :fact_values_transform
    ["id" "encode(value_hash::bytea, 'hex') as value_hash" "value_type_id"
     "value_integer" "value_float" "value_string" "value_boolean" "value"]
    #(-> %
         (update :value (comp sutils/munge-jsonb-for-storage json/parse-string))
         (update :value_hash sutils/munge-hash-for-storage)))

  (jdbc/do-commands
    "alter table facts drop constraint fact_value_id_fk"

    "drop table fact_values"
    "alter table fact_values_transform rename to fact_values"

    "alter table fact_values rename constraint fact_values_transform_value_hash_key
     to fact_values_value_hash_key"

    "alter table fact_values rename constraint fact_values_transform_pkey
     to fact_values_pkey"

    "alter table facts add constraint fact_value_id_fk foreign key
     (fact_value_id) references fact_values(id) on update restrict on delete restrict"
    "create index fact_values_value_float_idx on fact_values(value_float)"
    "create index fact_values_value_integer_idx on fact_values(value_integer)"))

(defn add-corrective-change-index
  []
  (jdbc/do-commands
    "CREATE INDEX resource_events_status_for_corrective_change_idx ON resource_events (status) WHERE corrective_change"))

(defn drop-resource-events-resource-type-idx
  []
  (jdbc/do-commands
    "DROP INDEX IF EXISTS resource_events_resource_type_idx"))

(defn merge-fact-values-into-facts
  []
  (jdbc/do-commands
   ["create table facts_transform"
    "  (factset_id bigint not null,"
    "   fact_path_id bigint not null,"
    "   value_integer bigint,"
    "   value_float double precision,"
    "   value_type_id bigint not null,"
    "   value_string text,"
    "   value jsonb,"
    "   large_value_hash bytea,"
    "   value_boolean boolean)"]

   ["insert into facts_transform"
    "  (factset_id,"
    "   fact_path_id,"
    "   value_integer,"
    "   value_float,"
    "   value_type_id,"
    "   value_string,"
    "   value,"
    "   large_value_hash,"
    "   value_boolean)"
    "  select factset_id, fact_path_id,"
    "         value_integer, value_float, value_type_id, value_string, value,"
    "         case when pg_column_size(value) >= 50"
    "              then value_hash"
    "         end,"
    "         value_boolean"
    "    from facts inner join fact_values on fact_value_id = fact_values.id"]

   "drop index facts_fact_value_id_idx"
   "drop table facts"
   "drop table fact_values"
   "drop sequence fact_values_id_seq"

    "alter table facts_transform rename to facts"

    "create index facts_factset_id_idx on facts using btree (factset_id)"
    "create index facts_fact_path_id_idx on facts using btree (fact_path_id)"
    "create index facts_value_integer_idx on facts(value_integer)"
    "create index facts_value_float_idx on facts(value_float)"

    ["alter table facts add constraint facts_value_type_id_fk"
     "  foreign key (value_type_id) references value_types (id) match simple"
     "    on update restrict on delete restrict"]))

(defn add-package-tables []
  (jdbc/do-commands
   ["create table packages "
    "  (id bigint PRIMARY KEY, "
    "   hash bytea, "
    "   name text not null, "
    "   provider text not null, "
    "   version text not null)"]

   "ALTER TABLE certnames ADD COLUMN package_hash bytea"

   "CREATE SEQUENCE package_id_seq CYCLE"
   "ALTER TABLE packages ALTER COLUMN id SET DEFAULT nextval('package_id_seq')"

   ["ALTER TABLE ONLY packages "
    "ADD CONSTRAINT package_hash_key UNIQUE (hash)"]

   ["create table certname_packages"
    "  (certname_id bigint not null,"
    "   package_id bigint not null,"
    "   PRIMARY KEY (certname_id, package_id),"
    "   FOREIGN KEY (certname_id) REFERENCES certnames(id),"
    "   FOREIGN KEY (package_id) REFERENCES packages(id))"]

   "create index certname_package_reverse_idx on certname_packages using btree (package_id, certname_id)"
   "create index packages_name_idx on packages using btree (name)"))

(defn add-gin-index-on-resource-params-cache []
  (jdbc/do-commands
    "create index resource_params_cache_parameters_idx on resource_params_cache using gin (parameters)"))

(defn improve-facts-factset-id-index []
  (jdbc/do-commands
    "DROP INDEX IF EXISTS facts_factset_id_idx"
    "DROP INDEX IF EXISTS facts_factset_id_fact_path_id_idx"
    "CREATE INDEX facts_factset_id_fact_path_id_idx ON facts(factset_id, fact_path_id)"))

(defn fix-missing-edges-fk-constraint []
  (when-not (sutils/constraint-exists? "certnames" "edges_certname_fkey")
    (log/info (trs "Cleaning up orphaned edges"))

    (jdbc/do-commands
     (str "SELECT e.*"
          "  INTO edges_transform"
          "  FROM edges e"
          "  INNER JOIN certnames c ON e.certname = c.certname")
     (str "ALTER TABLE edges_transform"
          "  ALTER COLUMN certname SET NOT NULL")
     (str "ALTER TABLE edges_transform"
          "  ALTER COLUMN source SET NOT NULL")
     (str "ALTER TABLE edges_transform"
          "  ALTER COLUMN target SET NOT NULL")
     (str "ALTER TABLE edges_transform"
          "  ALTER COLUMN type SET NOT NULL")
     (str "DROP TABLE edges")
     (str "ALTER TABLE edges_transform RENAME TO edges")
     (str "ALTER TABLE ONLY edges ADD CONSTRAINT edges_certname_fkey"
          "  FOREIGN KEY (certname)"
          "  REFERENCES certnames(certname)"
          "  ON DELETE CASCADE")
     (str "ALTER TABLE ONLY edges"
          "  ADD CONSTRAINT edges_certname_source_target_type_unique_key"
          "  UNIQUE (certname, source, target, type)"))))

(defn add-latest-report-timestamp-to-certnames []
  (jdbc/do-commands
    "DROP INDEX IF EXISTS idx_certnames_latest_report_timestamp"
    "ALTER TABLE certnames DROP COLUMN IF EXISTS latest_report_timestamp"
    "ALTER TABLE certnames ADD COLUMN latest_report_timestamp timestamp with time zone"
    "CREATE INDEX idx_certnames_latest_report_timestamp ON certnames(latest_report_timestamp)"))

(defn reports-partial-indices
  []
  (jdbc/do-commands
   "create index reports_noop_idx on reports(noop) where noop = true"
   "create index reports_cached_catalog_status_on_fail on reports(cached_catalog_status) where cached_catalog_status = 'on_failure'"))

(defn add-job-id []
  (jdbc/do-commands
   "alter table reports add column job_id text default null"
   "alter table catalogs add column job_id text default null"
   "create index reports_job_id_idx on reports(job_id) where job_id is not null"
   "create index catalogs_job_id_idx on catalogs(job_id) where job_id is not null"))

(defn maybe-parse-json [s]
  (some-> s json/parse))

(defn rededuplicate-facts []
  (log/info (trs "[1/8] Cleaning up unreferenced facts..."))
  (jdbc/do-commands
   "DELETE FROM facts WHERE factset_id NOT IN (SELECT id FROM factsets)"
   "DELETE FROM facts WHERE fact_path_id NOT IN (SELECT id FROM fact_paths)")

  (log/info (trs "[2/8] Creating new fact storage tables..."))
  (jdbc/do-commands
   "CREATE SEQUENCE fact_values_id_seq;"

   ;; will add not-null constraint on value_hash below; we don't have all the
   ;; values for this yet
   "CREATE TABLE fact_values (
      id bigint DEFAULT nextval('fact_values_id_seq'::regclass) NOT NULL,
      value_hash bytea,
      value_type_id bigint NOT NULL,
      value_integer bigint,
      value_float double precision,
      value_string text,
      value_boolean boolean,
      value jsonb
    );"

   ;; Do this early to help with the value_hash update queries below
   "ALTER TABLE fact_values ADD CONSTRAINT fact_values_pkey PRIMARY KEY (id);"

   "CREATE TABLE facts_transform (
       factset_id bigint NOT NULL,
       fact_path_id bigint NOT NULL,
       fact_value_id bigint NOT NULL
    );")

  (log/info (trs "[3/8] Copying unique fact values into fact_values"))
  (jdbc/do-commands
   "INSERT INTO fact_values (value, value_integer, value_float, value_string, value_boolean, value_type_id)
       SELECT distinct value, value_integer, value_float, value_string, value_boolean, value_type_id FROM facts")


  ;; Handle null fv.value separately; allowing them here leads to an intractable
  ;; query plan
  (log/info (trs "[4/8] Reconstructing facts to refer to fact_values..."))
  (jdbc/do-commands
   "INSERT INTO facts_transform (factset_id, fact_path_id, fact_value_id)
       SELECT f.factset_id, f.fact_path_id, fv.id
         FROM facts f
        INNER JOIN fact_values fv
                ON fv.value_type_id = f.value_type_id
               AND fv.value = f.value
               AND fv.value_integer IS NOT DISTINCT FROM f.value_integer
               AND fv.value_float   IS NOT DISTINCT FROM f.value_float
               AND fv.value_string  IS NOT DISTINCT FROM f.value_string
               AND fv.value_boolean IS NOT DISTINCT FROM f.value_boolean
        WHERE f.value IS NOT NULL AND fv.value IS NOT NULL"

   "INSERT INTO facts_transform (factset_id, fact_path_id, fact_value_id)
       SELECT f.factset_id, f.fact_path_id, fv.id
         FROM facts f
        INNER JOIN fact_values fv
                ON fv.value_type_id = f.value_type_id
               AND fv.value IS NOT DISTINCT FROM f.value
        WHERE f.value IS NULL AND fv.value IS NULL")

  (log/info (trs "[5/8] Cleaning up duplicate null values..."))
  ;; only do this if the DB has some null values
  (when (pos? (-> (jdbc/query-to-vec "select count(*) from fact_values
                                      where value_type_id in (4,5)
                                      and (value = 'null' or value is null)")
                  first
                  :count))
    (let [existing-id (some-> (jdbc/query-to-vec
                               "select id from fact_values
                                where value_type_id=4 and value is null")
                              first
                              :id)
          real-null-value-id (or existing-id
                                 (-> (jdbc/query-to-vec
                                      "insert into fact_values (value_type_id, value) values (4, null) returning id")
                                     first
                                     :id))
          bad-null-value-ids (->> (jdbc/query-to-vec "select id from fact_values
                                                      where (value_type_id = 5 and value = 'null')
                                                         or (value_type_id = 5 and value is null)
                                                         or (value_type_id = 4 and value = 'null')")
                                 (map :id))]
     (doseq [id bad-null-value-ids]
       (jdbc/do-prepared "update facts_transform set fact_value_id = (?) where fact_value_id = (?)"
                         [real-null-value-id id])
       (jdbc/do-prepared "delete from fact_values where id = (?)"
                         [id]))))

  (log/info (trs "[6/8] Computing fact value hashes..."))
  (jdbc/call-with-query-rows
   ["select id, value::text from fact_values"]
   (fn [rows]
     (doseq [batch (partition-all 500 rows)]
       (let [ids (map :id batch)
             hashes (map #(-> (:value %)
                              maybe-parse-json
                              hash/generic-identity-hash
                              sutils/munge-hash-for-storage)
                         batch)]
         (jdbc/do-prepared
          "update fact_values set value_hash = in_data.hash
            from (select unnest(?) as id, unnest(?) as hash) in_data
            where fact_values.id = in_data.id"
          [(sutils/array-to-param "bigint" Long ids)
           (sutils/array-to-param "bytea" PGobject hashes)])))))

  (log/info (trs "[7/8] Indexing fact_values table..."))
  (jdbc/do-commands
   "DROP TABLE facts"
   "ALTER TABLE facts_transform rename to facts"

   "ALTER TABLE fact_values alter column value_hash set not null"
   "ALTER TABLE fact_values ADD CONSTRAINT fact_values_value_hash_key UNIQUE (value_hash);"
   "CREATE INDEX fact_values_value_float_idx ON fact_values USING btree (value_float);"
   "CREATE INDEX fact_values_value_integer_idx ON fact_values USING btree (value_integer);")

  (log/info (trs "[8/8] Indexing facts table..."))
  (jdbc/do-commands
   "ALTER TABLE facts ADD CONSTRAINT facts_factset_id_fact_path_id_fact_key UNIQUE (factset_id, fact_path_id);"
   "CREATE INDEX facts_fact_path_id_idx ON facts USING btree (fact_path_id);"
   "CREATE INDEX facts_fact_value_id_idx ON facts USING btree (fact_value_id);"

   "ALTER TABLE facts ADD CONSTRAINT fact_path_id_fk
      FOREIGN KEY (fact_path_id)
      REFERENCES fact_paths(id);"

   "ALTER TABLE facts ADD CONSTRAINT fact_value_id_fk
      FOREIGN KEY (fact_value_id)
      REFERENCES fact_values(id)
      ON UPDATE RESTRICT
      ON DELETE RESTRICT;"

   "ALTER TABLE facts ADD CONSTRAINT factset_id_fk FOREIGN KEY (factset_id) REFERENCES factsets(id) ON UPDATE CASCADE ON DELETE CASCADE;")

  {::vacuum-analyze #{"facts" "fact_values" "fact_paths"}})

(defn varchar-columns-to-text []
  (jdbc/do-commands
    "alter table reports
     alter column puppet_version type text"

    "alter table reports
     alter column configuration_version type text"

    "alter table resource_events
     alter column status type text"

    "alter table resource_events
     alter column property type text"

    "alter table resource_events
     alter column containing_class type text"

    "alter table resource_events
     alter column file type text"))

(defn jsonb-facts []
  (jdbc/do-commands
   "alter table factsets add column paths_hash bytea"  ;; Can be null atm
   "alter table factsets add column stable jsonb"
   "alter table factsets add column stable_hash bytea"
   "alter table factsets add column volatile jsonb"
   "create index idx_factsets_jsonb_merged on factsets using gin((stable||volatile) jsonb_path_ops)"

   "update factsets fs
    set stable = (select json_object_agg(name, value)
                 from (
                 select f.factset_id, fp.name, fv.value from facts f
                 inner join fact_values fv on fv.id = f.fact_value_id
                 inner join fact_paths fp on fp.id = f.fact_path_id
                 inner join value_types vt on vt.id = fv.value_type_id
                 where fp.depth = 0
                 ) s where fs.id = s.factset_id),
    volatile = jsonb('{}')"

   "drop table facts"
   "drop table fact_values"
   "alter table fact_paths drop constraint fact_paths_path_key"

   ;; TODO consider migrating fact paths - maybe not worth it. This table will
   ;; be mostly repopulated on reception of first factset, and fully
   ;; repopulated by the time runinterval has elasped. It also only matters to
   ;; the fact-paths endpoint now.
   "truncate table fact_paths"

   "alter table fact_paths add column path_array text[] not null"
   "alter table fact_paths add column value_type_id int not null"
   "alter table fact_paths add constraint fact_paths_path_type_unique unique(path, value_type_id)")

  {::vacuum-analyze #{"factsets"}})

(defn support-fact-expiration-configuration []
  ;; Note that a missing row implies "true", i.e. expiration should
  ;; behave as it always had, and as it does for agent managed nodes.
  (jdbc/do-commands
   ["create table certname_fact_expiration"
    "  (certid bigint not null primary key,"
    "   expire bool not null,"
    "   updated timestamp with time zone not null,"
    "   constraint certname_fact_expiration_certid_fkey"
    "     foreign key (certid) references certnames(id) on delete cascade,"
    "   constraint certname_fact_expiration_expire_updated_vals_match"
    "     check ((expire is not null and updated is not null)"
    "             or (expire is null and updated is null)))"]))

(defn add-support-for-catalog-inputs []
  (jdbc/do-commands
   "ALTER TABLE certnames ADD COLUMN catalog_inputs_timestamp TIMESTAMP WITH TIME ZONE"
   "ALTER TABLE certnames ADD COLUMN catalog_inputs_uuid uuid")

  (jdbc/do-commands
   ["CREATE TABLE catalog_inputs"
    "  (certname_id BIGINT NOT NULL,"
    "   type TEXT NOT NULL,"
    "   name TEXT NOT NULL,"
    "   CONSTRAINT catalog_inputs_certname_id_fkey"
    "     FOREIGN KEY (certname_id) REFERENCES certnames(id) ON DELETE CASCADE)"])

  ;; TODO: figure out what additional indexes we want in the catalog inputs table
  (jdbc/do-commands
   "CREATE INDEX catalog_inputs_certname_id_index ON catalog_inputs (certname_id)"))


(defn migrate-md5-to-sha1-hashes []
  ;; Existing puppetdb installations will have a dual_md5 function
  ;; and a md5_agg aggregate function. We are replacing them with
  ;; SHA-1 based versions. A brand new puppetdb installation will
  ;; only have the SHA-1 versions, because the earlier migrations
  ;; were changed.

  (jdbc/do-commands
   ;; handle the case where this is a brand new installation of puppetdb
   "DROP AGGREGATE IF EXISTS sha1_agg(BYTEA)"
   "DROP FUNCTION IF EXISTS dual_sha1(BYTEA, BYTEA)"

   ;; both new and existing installations will need these created
   "CREATE FUNCTION dual_sha1(BYTEA, BYTEA) RETURNS bytea AS $$
      BEGIN
        RETURN digest($1 || $2, 'sha1');
      END;
    $$ LANGUAGE plpgsql"
   "CREATE AGGREGATE sha1_agg (BYTEA)
    (
      sfunc = dual_sha1,
      stype = bytea,
      initcond = '\\x00'
    )"

   ;; existing puppetdb installations will have these functions, which
   ;; are now not used
   "DROP AGGREGATE IF EXISTS md5_agg(BYTEA)"
   "DROP FUNCTION IF EXISTS dual_md5(BYTEA, BYTEA)"))

(defn autovacuum-vacuum-scale-factor-factsets-catalogs-certnames []
  (jdbc/do-commands
   "ALTER TABLE factsets  SET ( autovacuum_vacuum_scale_factor=0.80 )"
   "ALTER TABLE catalogs  SET ( autovacuum_vacuum_scale_factor=0.75 )"
   "ALTER TABLE certnames SET ( autovacuum_vacuum_scale_factor=0.75 )"))

;; for testing via with-redefs
;; used to account for the possibility of the name column being added in the
;; original version of migration 69 before a user has applied migration 73
(defn migration-69-stub [])

(s/defn create-original-partition
  "Creates an inheritance partition for the historical state of migrations #73 and #74"
  [base-table :- s/Str
   date-column :- s/Str
   date :- (s/cond-pre LocalDate LocalDateTime ZonedDateTime Instant java.sql.Timestamp)
   constraint-fn :- (s/fn-schema
                     (s/fn :- [s/Str] [_iso-year-week :- s/Str]))
   index-fn :- (s/fn-schema
                (s/fn :- [s/Str] [_full-table-name :- s/Str
                                  _iso-year-week :- s/Str]))]
  (let [date (partitioning/to-zoned-date-time date)                      ;; guarantee a ZonedDateTime, so our suffix ends in Z
        start-of-day (-> date
                         (.truncatedTo (ChronoUnit/DAYS)))  ;; this is a ZonedDateTime
        start-of-next-day (-> start-of-day
                              (.plusDays 1))
        date-formatter (DateTimeFormatter/ISO_OFFSET_DATE_TIME)

        table-name-suffix (partitioning/date-suffix date)
        full-table-name (format "%s_%s" base-table table-name-suffix)]
    (apply jdbc/do-commands
           (concat [(format (str "CREATE TABLE IF NOT EXISTS %s ("
                                 (str/join ", "
                                          (cons "CHECK ( %s >= TIMESTAMP WITH TIME ZONE '%s' AND %s < TIMESTAMP WITH TIME ZONE '%s' )"
                                                (constraint-fn table-name-suffix)))
                                 ") INHERITS (%s)")
                            full-table-name
                            ;; this will write the constraint in UTC. note: when you read this back from the database,
                            ;; you will get it in local time.
                            ;; example: constraint will have 2019-09-21T00:00:00Z but upon querying, you'll see 2019-09-21 17:00:00-07
                            ;; this is just the database performing i18n
                            date-column (.format start-of-day date-formatter) date-column (.format start-of-next-day date-formatter)
                            base-table)]
                   (index-fn full-table-name table-name-suffix)))))

(defn create-original-resource-events-partition
  "Creates an inheritance partition in the resource_events table for migration #73"
  [date]
  (create-original-partition
   "resource_events" "\"timestamp\""
   date
   (constantly [])
   (fn [full-table-name iso-week-year]
     [(format "CREATE INDEX IF NOT EXISTS resource_events_containing_class_idx_%s ON %s USING btree (containing_class)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_property_idx_%s ON %s USING btree (property)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_reports_id_idx_%s ON %s USING btree (report_id)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_resource_timestamp_%s ON %s USING btree (resource_type, resource_title, \"timestamp\")"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_resource_title_idx_%s ON %s USING btree (resource_title)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_status_for_corrective_change_idx_%s ON %s USING btree (status) WHERE corrective_change"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_status_idx_%s ON %s USING btree (status)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_timestamp_idx_%s ON %s USING btree (\"timestamp\")"
              iso-week-year full-table-name)

      (format "CREATE UNIQUE INDEX IF NOT EXISTS resource_events_hash_%s ON %s (event_hash)"
              iso-week-year full-table-name)])))

(defn resource-events-partitioning
  ([]
   (resource-events-partitioning 500))
  ([batch-size]
   (jdbc/do-commands
     "ALTER TABLE resource_events RENAME TO resource_events_premigrate"

     "CREATE TABLE resource_events (
        event_hash bytea NOT NULL PRIMARY KEY,
        report_id bigint NOT NULL,
        certname_id bigint NOT NULL,
        status text NOT NULL,
        \"timestamp\" timestamp with time zone NOT NULL,
        resource_type text NOT NULL,
        resource_title text NOT NULL,
        property text,
        new_value text,
        old_value text,
        message text,
        file text DEFAULT NULL::character varying,
        line integer,
        name text,
        containment_path text[],
        containing_class text,
        corrective_change boolean)"

     "CREATE OR REPLACE FUNCTION resource_events_insert_trigger()
     RETURNS TRIGGER AS $$
     DECLARE
       tablename varchar;
     BEGIN
       SELECT FORMAT('resource_events_%sZ',
                     TO_CHAR(NEW.\"timestamp\" AT TIME ZONE 'UTC', 'YYYYMMDD')) INTO tablename;

       EXECUTE 'INSERT INTO ' || tablename || ' SELECT ($1).*'
       USING NEW;

       RETURN NULL;
     END;
     $$
     LANGUAGE plpgsql;"

     "CREATE TRIGGER insert_resource_events_trigger
     BEFORE INSERT ON resource_events
     FOR EACH ROW EXECUTE PROCEDURE resource_events_insert_trigger();"

     "CREATE OR REPLACE FUNCTION find_resource_events_unique_dates()
     RETURNS TABLE (rowdate TIMESTAMP WITH TIME ZONE)
     AS $$
     DECLARE
     BEGIN
       EXECUTE 'SET local timezone to ''UTC''';
       RETURN QUERY SELECT DISTINCT date_trunc('day', \"timestamp\") AS rowdate FROM resource_events_premigrate;
     END;
     $$ language plpgsql;")

  ;; create range of partitioned tables

  (let [now (ZonedDateTime/now)
        days (range -4 4)]
    (doseq [day-offset days]
      (create-original-resource-events-partition (.plusDays now day-offset))))

  ;; null values are not considered equal in postgresql indexes,
  ;; therefore the existing unique constraint did not function
  ;; as intended. this will replace the unique constraint with
  ;; a primary key that is a hash of the four columns used to
  ;; consider uniqueness.

  ;; since the unique constraint could allow duplicates to exist
  ;; in the table, we have to keep a running set of the hashes
  ;; we've encountered during the migration to avoid inserting
  ;; duplicates into the new table.

   ;; pre-create partitions
   (log/info (trs "Creating partitions based on unique days in resource_events"))
   ;; PDB-4641 - PostgreSQL returns dates in the client's timezone. What we need
   ;; to do here is force the connection into UTC. We want the date of the event,
   ;; in UTC, so that we can create the partition for it. To do this, we have
   ;; the find_resource_events_unique_dates plpgsql function above. For the duration
   ;; of this migration transaction, dates will be displayed in UTC.
   ;; If the timezone is in the local timezone (say, EST, or CEST), you get the date
   ;; in that timezone with the time cut off. So, 2020-01-30T05:00:00Z, which truncates
   ;; to 2020-01-30 using the pgsql date functions. In reality, this is actually
   ;; 2020-01-31 in UTC, which is the date we need for creating the partition successfully.
   (let [current-timezone (:current_setting (first (jdbc/query-to-vec "SELECT current_setting('TIMEZONE')")))]
     (jdbc/call-with-query-rows
       ["select rowdate from find_resource_events_unique_dates()"]
       (fn [rows]
         (doseq [row rows]
           (create-original-resource-events-partition (-> (:rowdate row)
                                                              (.toInstant))))))
     (jdbc/do-commands
       ;; restore the transaction's timezone setting after creating the partitions
       (str "SET local timezone to '" current-timezone "'")))

  (let [event-count (-> "select count(*) from resource_events_premigrate"
                        jdbc/query-to-vec first :count)
        last-logged (atom (.getTime (java.util.Date.)))
        events-migrated (atom 0)
        ;; The name column was added in migration 69 before being replaced
        ;; by this migration. If a user has already applied the old version
        ;; of migration 69 account for the existing data in the name column.
        name-column? (->
                      "select exists (select 1
                                       from information_schema.columns
                                       where table_schema='public'
                                       and table_name='resource_events_premigrate'
                                       and column_name='name');"
                      jdbc/query-to-vec
                      first
                      :exists)]
    (jdbc/call-with-query-rows
     [(format
       "select * from (
                      select
                      report_id,
                      certname_id,
                      status,
                      timestamp,
                      resource_type,
                      resource_title,
                      property,
                      new_value,
                      old_value,
                      message,
                      file,
                      line,
                      containment_path,
                      containing_class,
                      corrective_change,
                      %s
                      row_number() over ( partition by
                                            report_id, resource_type, resource_title, property, timestamp,
                                            status, old_value, new_value, message, file, line
                                          order by timestamp asc )
                    from resource_events_premigrate
                  ) as sub
                  where row_number = 1"
       (if name-column? "name," ""))]
     (fn [rows]
      (let [old-cols [:report_id :certname_id :status :timestamp :resource_type
                      :resource_title :property :new_value :old_value :message
                      :file :line :containment_path :containing_class
                      :corrective_change]
            old-cols (if name-column?
                       (conj old-cols :name)
                       old-cols)
            new-cols (into [:event_hash] old-cols)
            update-row (apply juxt (comp sutils/munge-hash-for-storage
                                         hash/resource-event-identity-pkey)
                              old-cols)
            insert->hash (fn [batch]
                           (swap! events-migrated + (count batch))
                           (let [batch (map update-row batch)]
                             (when (seq batch)
                               (doseq [g (group-by (fn [o] (-> (get o 4)
                                                               (partitioning/to-zoned-date-time)
                                                               (partitioning/date-suffix))) batch)]
                                 (jdbc/insert-multi! (str "resource_events_" (first g))
                                                     new-cols
                                                     (last g))))
                             (let [now (.getTime (java.util.Date.))]
                               (when (> (- now @last-logged) 60000)
                                 (maplog :info
                                         {:migration 73 :at @events-migrated :of event-count}
                                         #(trs "Migrated {0} of {1} events" (:at %) (:of %)))
                                 (reset! last-logged now)))))]
        (dorun (map insert->hash (partition batch-size batch-size [] rows)))))))

  (jdbc/do-commands
   ;; Indexes are created on the individual partitions, not on the base table
   "DROP TABLE resource_events_premigrate"
   "DROP FUNCTION find_resource_events_unique_dates()")))

(defn create-original-reports-partition
  "Creates an inheritance partition in the reports table for migration #74"
  [date]
  (create-original-partition
    "reports" "\"producer_timestamp\""
    date
    (fn [iso-week-year]
      [(format (str "CONSTRAINT reports_certname_fkey_%s"
                    " FOREIGN KEY (certname) REFERENCES certnames(certname) ON DELETE CASCADE")
               iso-week-year)
       (format (str "CONSTRAINT reports_env_fkey_%s"
                    " FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE")
               iso-week-year)
       (format (str "CONSTRAINT reports_prod_fkey_%s"
                    " FOREIGN KEY (producer_id) REFERENCES producers(id)")
               iso-week-year)
       (format (str "CONSTRAINT reports_status_fkey_%s"
                    " FOREIGN KEY (status_id) REFERENCES report_statuses(id) ON DELETE CASCADE")
               iso-week-year)])
    (fn [full-table-name iso-week-year]
      [(format "CREATE INDEX IF NOT EXISTS idx_reports_compound_id_%s ON %s USING btree (producer_timestamp, certname, hash) WHERE (start_time IS NOT NULL)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS idx_reports_noop_pending_%s ON %s USING btree (noop_pending) WHERE (noop_pending = true)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS idx_reports_prod_%s ON %s USING btree (producer_id)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS idx_reports_producer_timestamp_%s ON %s USING btree (producer_timestamp)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS idx_reports_producer_timestamp_by_hour_certname_%s ON %s USING btree (date_trunc('hour'::text, timezone('UTC'::text, producer_timestamp)), producer_timestamp, certname)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_cached_catalog_status_on_fail_%s ON %s USING btree (cached_catalog_status) WHERE (cached_catalog_status = 'on_failure'::text)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_catalog_uuid_idx_%s ON %s USING btree (catalog_uuid)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_certname_idx_%s ON %s USING btree (certname)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_end_time_idx_%s ON %s USING btree (end_time)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_environment_id_idx_%s ON %s USING btree (environment_id)"
               iso-week-year full-table-name)
       (format "CREATE UNIQUE INDEX IF NOT EXISTS reports_hash_expr_idx_%s ON %s USING btree (encode(hash, 'hex'::text))"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_job_id_idx_%s ON %s USING btree (job_id) WHERE (job_id IS NOT NULL)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_noop_idx_%s ON %s USING btree (noop) WHERE (noop = true)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_status_id_idx_%s ON %s USING btree (status_id)"
               iso-week-year full-table-name)
       (format "CREATE INDEX IF NOT EXISTS reports_tx_uuid_expr_idx_%s ON %s USING btree (((transaction_uuid)::text))"
               iso-week-year full-table-name)])))

(defn reports-partitioning []
  (jdbc/do-commands
   ;; detach the sequence so it isn't modified during the migration
   "ALTER SEQUENCE reports_id_seq OWNED BY NONE"

   "DROP INDEX idx_reports_noop_pending"
   "DROP INDEX idx_reports_prod"
   "DROP INDEX idx_reports_producer_timestamp"
   "DROP INDEX idx_reports_producer_timestamp_by_hour_certname"
   "DROP INDEX reports_cached_catalog_status_on_fail"
   "DROP INDEX reports_catalog_uuid_idx"
   "DROP INDEX reports_certname_idx"
   "DROP INDEX reports_end_time_idx"
   "DROP INDEX reports_environment_id_idx"
   "DROP INDEX reports_hash_expr_idx"
   "DROP INDEX reports_job_id_idx"
   "DROP INDEX reports_noop_idx"
   "DROP INDEX reports_status_id_idx"
   "DROP INDEX reports_tx_uuid_expr_idx"
   "ALTER TABLE certnames DROP CONSTRAINT certnames_reports_id_fkey"

   "ALTER TABLE reports DROP CONSTRAINT reports_pkey"

   "ALTER TABLE reports RENAME TO reports_premigrate"

   "CREATE TABLE reports (
      id bigint NOT NULL,
      hash bytea NOT NULL,
      transaction_uuid uuid,
      certname text NOT NULL,
      puppet_version text NOT NULL,
      report_format smallint NOT NULL,
      configuration_version text NOT NULL,
      start_time timestamp with time zone NOT NULL,
      end_time timestamp with time zone NOT NULL,
      receive_time timestamp with time zone NOT NULL,
      noop boolean,
      environment_id bigint,
      status_id bigint,
      metrics_json json,
      logs_json json,
      producer_timestamp timestamp with time zone NOT NULL,
      metrics jsonb,
      logs jsonb,
      resources jsonb,
      catalog_uuid uuid,
      cached_catalog_status text,
      code_id text,
      producer_id bigint,
      noop_pending boolean,
      corrective_change boolean,
      job_id text)"

   "CREATE OR REPLACE FUNCTION find_reports_unique_dates()
    RETURNS TABLE (rowdate TIMESTAMP WITH TIME ZONE)
    AS $$
    DECLARE
    BEGIN
      EXECUTE 'SET local timezone to ''UTC''';
      RETURN QUERY SELECT DISTINCT date_trunc('day', producer_timestamp) AS rowdate FROM reports_premigrate;
    END;
    $$ language plpgsql;")

  ;; note: the reports table does *not* have an insert trigger because reports relies on the RETURNING *
  ;; part of the INSERT INTO statement.

  ;; create range of partitioned tables

  (let [now (ZonedDateTime/now)
        days (range -4 4)]
    (doseq [day-offset days]
      (create-original-reports-partition (.plusDays now day-offset))))

  ;; pre-create partitions
  (log/info (trs "Creating partitions based on unique days in reports"))
  (let [current-timezone (:current_setting (first (jdbc/query-to-vec "SELECT current_setting('TIMEZONE')")))]
    (jdbc/call-with-query-rows
     ["select rowdate from find_reports_unique_dates()"]
     (fn [rows]
       (doseq [row rows]
         (create-original-reports-partition (-> (:rowdate row)
                                                (.toInstant))))))
    (jdbc/do-commands
     ;; restore the transaction's timezone setting after creating the partitions
     (str "SET local timezone to '" current-timezone "'")))

  (let [event-count (-> "select count(*) from reports_premigrate"
                        jdbc/query-to-vec first :count)
        last-logged (atom (.getTime (java.util.Date.)))
        reports-migrated (atom 0)]
    (jdbc/call-with-query-rows
      ["select * from (
          select
            id, transaction_uuid, certname, puppet_version, report_format, configuration_version, start_time, end_time,
            receive_time, noop, environment_id, status_id, metrics_json, logs_json, producer_timestamp, metrics, logs,
            resources, catalog_uuid, cached_catalog_status, code_id, producer_id, noop_pending, corrective_change,
            job_id,
            row_number() over ( partition by
                                  certname, puppet_version, report_format, configuration_version, start_time, end_time,
                                  receive_time, transaction_uuid
                                order by receive_time asc )
          from reports_premigrate) as sub
        where row_number = 1"]
      ;; set the FetchSize used by jdbc to avoid any potential OOM errors caused
      ;; by fetching too many large reports at once
      {:fetch-size 1}
      (fn [rows]
        (let [old-cols [:id :transaction_uuid :certname :puppet_version :report_format
                        :configuration_version :start_time :end_time :receive_time :noop
                        :environment_id :status_id :metrics_json :logs_json :producer_timestamp
                        :metrics :logs :resources :catalog_uuid :cached_catalog_status :code_id
                        :producer_id :noop_pending :corrective_change :job_id]
              new-cols (into [:hash] old-cols)
              update-row (apply juxt (comp sutils/munge-hash-for-storage
                                           hash/report-identity-hash)
                                old-cols)
              insert->hash (fn [row]
                             (swap! reports-migrated inc)
                             (let [updated-row (update-row row)
                                   table-name (str "reports_" (-> updated-row
                                                                  (get 15) ;; producer_timestamp
                                                                  (partitioning/to-zoned-date-time)
                                                                  (partitioning/date-suffix)))]
                               (jdbc/insert! table-name new-cols updated-row))
                             (let [now (.getTime (java.util.Date.))]
                               (when (> (- now @last-logged) 60000)
                                 (maplog :info
                                         {:migration 74 :at @reports-migrated :of event-count}
                                         #(trs "Migrated {0} of {1} reports" (:at %) (:of %)))
                                 (reset! last-logged now))))]
          (run! insert->hash rows)))))

  (jdbc/do-commands
   ;; attach sequence
   "ALTER SEQUENCE reports_id_seq OWNED BY reports.id"

   ;; set default value on new table DEFAULT nextval('reports_id_seq'::regclass)
   "ALTER TABLE reports ALTER COLUMN id SET DEFAULT nextval('reports_id_seq'::regclass)"

   "ALTER TABLE reports ADD CONSTRAINT reports_pkey PRIMARY KEY (id)"
   "CREATE INDEX idx_reports_noop_pending ON reports USING btree (noop_pending) WHERE (noop_pending = true)"
   "CREATE INDEX idx_reports_prod ON reports USING btree (producer_id)"
   "CREATE INDEX idx_reports_producer_timestamp ON reports USING btree (producer_timestamp)"
   ["CREATE INDEX idx_reports_producer_timestamp_by_hour_certname ON reports USING btree "
    "  (date_trunc('hour'::text, timezone('UTC'::text, producer_timestamp)), producer_timestamp, certname)"]
   ["CREATE INDEX reports_cached_catalog_status_on_fail ON reports USING btree"
    "  (cached_catalog_status) WHERE (cached_catalog_status = 'on_failure'::text)"]
   "CREATE INDEX reports_catalog_uuid_idx ON reports USING btree (catalog_uuid)"
   "CREATE INDEX reports_certname_idx ON reports USING btree (certname)"
   "CREATE INDEX reports_end_time_idx ON reports USING btree (end_time)"
   "CREATE INDEX reports_environment_id_idx ON reports USING btree (environment_id)"
   "CREATE UNIQUE INDEX reports_hash_expr_idx ON reports USING btree (encode(hash, 'hex'::text))"
   "CREATE INDEX reports_job_id_idx ON reports USING btree (job_id) WHERE (job_id IS NOT NULL)"
   "CREATE INDEX reports_noop_idx ON reports USING btree (noop) WHERE (noop = true)"
   "CREATE INDEX reports_status_id_idx ON reports USING btree (status_id)"
   "CREATE INDEX reports_tx_uuid_expr_idx ON reports USING btree (((transaction_uuid)::text))"
   ["ALTER TABLE reports"
    "  ADD CONSTRAINT reports_certname_fkey FOREIGN KEY (certname) REFERENCES certnames(certname) ON DELETE CASCADE"]
   ["ALTER TABLE reports"
    "  ADD CONSTRAINT reports_env_fkey FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE"]
   ["ALTER TABLE reports"
    "  ADD CONSTRAINT reports_prod_fkey FOREIGN KEY (producer_id) REFERENCES producers(id)"]
   ["ALTER TABLE reports"
    "  ADD CONSTRAINT reports_status_fkey FOREIGN KEY (status_id) REFERENCES report_statuses(id) ON DELETE CASCADE"]

   "DROP TABLE reports_premigrate"
   "DROP FUNCTION find_reports_unique_dates()"))

(defn require-schema-migrations-table
  []
  ;; This must be completely idempotent since we run it as a migration
  ;; and manually (to make sure it's there for the access lock).
  (jdbc/do-commands
   ["create table if not exists schema_migrations"
    "  (version integer not null primary key,"
    "   \"time\" timestamp without time zone not null)"]))

(defn add-report-type-to-reports
  "Add a 'report_type' column to the 'reports' table, to indicate the
  source of the report. Valid values at the time of this migration are
  'agent' or 'plan'."
  []
  (jdbc/do-commands
   "ALTER TABLE reports ADD COLUMN report_type text DEFAULT 'agent' NOT NULL"))

(defn add-report-partition-indexes-on-id
  []
  (doseq [{:keys [table part]} (get-temporal-partitions "reports")
          :let [idx-name (str "idx_reports_id_" part)]]
    (jdbc/do-commands
     (format "create unique index if not exists %s on %s using btree (id)"
             (jdbc/double-quote idx-name)
             (jdbc/double-quote table)))))

(defn add-report-partition-indexes-on-certname-end-time
  []
  (doseq [{:keys [table part]} (get-temporal-partitions "reports")
          :let [old-idx-name (str "reports_certname_idx_" part)
                new-idx-name (str "idx_reports_certname_end_time_" part)]]
    (jdbc/do-commands
     (format "drop index if exists %s"
             (jdbc/double-quote old-idx-name))
     (format "create index if not exists %s on %s using btree (certname,end_time)"
             (jdbc/double-quote new-idx-name)
             (jdbc/double-quote table))))
  (jdbc/do-commands
   "drop index reports_certname_idx"
   "create index idx_reports_certname_end_time on reports using btree (certname,end_time)"))

(defn add-catalog-inputs-pkey
  []
  (jdbc/do-commands
   "ALTER TABLE catalog_inputs ADD CONSTRAINT catalog_inputs_pkey PRIMARY KEY (type, name, certname_id)"))

(defn add-catalog-inputs-hash
  []
  (jdbc/do-commands
   "ALTER TABLE certnames ADD COLUMN catalog_inputs_hash bytea"))

(defn add-workspaces-tables
  []
  (jdbc/do-commands
   ["CREATE TABLE workspaces ("
    "  uuid UUID PRIMARY KEY,"
    "  updated TIMESTAMP WITH TIME ZONE NOT NULL)"]

   ["CREATE TABLE workspace_memberships ("
    "  workspace_uuid UUID,"
    "  certname TEXT NOT NULL,"
    "  PRIMARY KEY (workspace_uuid, certname),"
    "  FOREIGN KEY (workspace_uuid) REFERENCES workspaces(uuid) ON DELETE CASCADE)"]))

(defn reattach-partitions
  "Detaches partition children from PostgreSQL inheritance and attaches them to
  the new partition table (under its placeholder name)."
  [table child-table-maps]
  (let [placeholder-name (format "%s_partitioned" table)]
    (apply jdbc/do-commands
      (flatten
        (map (fn [child-map]
               (let [child (:table child-map)
                     date (:part child-map)
                     basic-iso-date-formatter (DateTimeFormatter/BASIC_ISO_DATE)
                     iso-offset-date-formatter (DateTimeFormatter/ISO_OFFSET_DATE_TIME)
                     start (partitioning/to-zoned-date-time (LocalDate/parse date basic-iso-date-formatter))
                     end (-> start (.plusDays 1))]
                 [
                   (format "ALTER TABLE %s NO INHERIT %s" child table)
                   (format
                      "ALTER TABLE %s
                       ATTACH PARTITION %s
                       FOR VALUES FROM ('%s') TO ('%s')"
                      placeholder-name child
                      (.format start iso-offset-date-formatter)
                      (.format end iso-offset-date-formatter))
                  ]))
              child-table-maps)))))

(defn drop-child-partition-constraints
  "Drops the redundant constraints previously placed on the child tables that
  were enforcing the partitioning for the inheritance partitions."
  [date-column child-table-maps]
  (apply jdbc/do-commands
    (map (fn [child-map]
           (let [child (:table child-map)
                 check-constraint-name (format "%s_%s_check" child date-column)]
             (format "ALTER TABLE %s DROP CONSTRAINT %s" child check-constraint-name)))
         child-table-maps)))

(defn drop-foreign-key-constraints
  "Drops the foreign keys previously placed on the child tables"
  [child-table-maps]
  (apply jdbc/do-commands
    (mapcat (fn [child-map]
           (let [child (:table child-map)
                 fkeys ["reports_certname_fkey" "reports_env_fkey" "reports_prod_fkey" "reports_status_fkey"]]
             (map (fn [fkey] (format "ALTER TABLE %s DROP CONSTRAINT %s_%s" child fkey (:part child-map)))
                  fkeys)))
         child-table-maps)))

(defn migrate-resource-events-to-declarative-partitioning
  []
  (let [table "resource_events"
        date-column "timestamp"
        child-table-maps (partitioning/get-temporal-partitions table)]
    ;; Create new partitioned table with placeholder name and original schema
    ;; as documented in migration 73 (resource-events-partitioning)
    (jdbc/do-commands
      (format "CREATE TABLE resource_events_partitioned (
         event_hash bytea NOT NULL,
         report_id bigint NOT NULL,
         certname_id bigint NOT NULL,
         status text NOT NULL,
         \"timestamp\" timestamp with time zone NOT NULL,
         resource_type text NOT NULL,
         resource_title text NOT NULL,
         property text,
         new_value text,
         old_value text,
         message text,
         file text DEFAULT NULL::character varying,
         line integer,
         name text,
         containment_path text[],
         containing_class text,
         corrective_change boolean) PARTITION BY RANGE (%s)" date-column))

    ;; Moving existing children to partitioned table
    (reattach-partitions table child-table-maps)

    ;; Drop redundant child constraints
    (drop-child-partition-constraints date-column child-table-maps)

    (jdbc/do-commands
      ;; Drop original table
      "DROP TABLE resource_events"
      ;; Rename partitioned table as actual table name
      "ALTER TABLE resource_events_partitioned RENAME TO resource_events")

    ;; Create indices on partitioned table
    (jdbc/do-commands
      "ALTER TABLE resource_events ADD CONSTRAINT resource_events_pkey PRIMARY KEY (event_hash, timestamp)"
      "CREATE INDEX IF NOT EXISTS resource_events_containing_class_idx ON resource_events USING btree (containing_class)"
      "CREATE INDEX IF NOT EXISTS resource_events_property_idx ON resource_events USING btree (property)"
      "CREATE INDEX IF NOT EXISTS resource_events_reports_id_idx ON resource_events USING btree (report_id)"
      "CREATE INDEX IF NOT EXISTS resource_events_resource_timestamp ON resource_events USING btree (resource_type, resource_title, \"timestamp\")"
      "CREATE INDEX IF NOT EXISTS resource_events_resource_title_idx ON resource_events USING btree (resource_title)"
      "CREATE INDEX IF NOT EXISTS resource_events_status_for_corrective_change_idx ON resource_events USING btree (status) WHERE corrective_change"
      "CREATE INDEX IF NOT EXISTS resource_events_status_idx ON resource_events USING btree (status)"
      "CREATE INDEX IF NOT EXISTS resource_events_timestamp_idx ON resource_events USING btree (\"timestamp\")")))

(defn migrate-reports-to-declarative-partitioning
  []
  (let [table "reports"
        date-column "producer_timestamp"
        child-table-maps (partitioning/get-temporal-partitions table)]
    ;; Create new partitioned table with placeholder name and original schema
    ;; as documented in migration 74 (reports-partitioning)
    (jdbc/do-commands
      (format "CREATE TABLE reports_partitioned (
         id bigint NOT NULL,
         hash bytea NOT NULL,
         transaction_uuid uuid,
         certname text NOT NULL,
         puppet_version text NOT NULL,
         report_format smallint NOT NULL,
         configuration_version text NOT NULL,
         start_time timestamp with time zone NOT NULL,
         end_time timestamp with time zone NOT NULL,
         receive_time timestamp with time zone NOT NULL,
         noop boolean,
         environment_id bigint,
         status_id bigint,
         metrics_json json,
         logs_json json,
         producer_timestamp timestamp with time zone NOT NULL,
         metrics jsonb,
         logs jsonb,
         resources jsonb,
         catalog_uuid uuid,
         cached_catalog_status text,
         code_id text,
         producer_id bigint,
         noop_pending boolean,
         corrective_change boolean,
         job_id text,
         report_type text DEFAULT 'agent' NOT NULL) PARTITION BY RANGE (%s)" date-column))

    ;; Moving existing children to partitioned table
    (reattach-partitions table child-table-maps)

    ;; Drop redundant child constraints
    (drop-child-partition-constraints date-column child-table-maps)

    ;; Drop existing foreign keys from partitions
    (drop-foreign-key-constraints child-table-maps)

    (jdbc/do-commands
      ;; Attach sequence and set it as default for reports.id (must be done before dropping reports)
      "ALTER SEQUENCE reports_id_seq OWNED BY reports_partitioned.id"
      "ALTER TABLE reports_partitioned ALTER COLUMN id SET DEFAULT nextval('reports_id_seq'::regclass)"

      ;; Drop original table
      "DROP TABLE reports"
      ;; Rename partitioned table as actual table name
      "ALTER TABLE reports_partitioned RENAME TO reports")

    ;; Recreate indices and constraints on partitioned table as performed for
    ;; migration 74 (reports-partitioning)
    (jdbc/do-commands
      ;; Recreate indices
      "ALTER TABLE reports ADD CONSTRAINT reports_pkey PRIMARY KEY (id, producer_timestamp)"
      "CREATE INDEX idx_reports_compound_id ON reports USING btree (producer_timestamp, certname, hash) WHERE (start_time IS NOT NULL)"
      "CREATE INDEX idx_reports_noop_pending ON reports USING btree (noop_pending) WHERE (noop_pending = true)"
      "CREATE INDEX idx_reports_prod ON reports USING btree (producer_id)"
      "CREATE INDEX idx_reports_producer_timestamp ON reports USING btree (producer_timestamp)"
      ["CREATE INDEX idx_reports_producer_timestamp_by_hour_certname ON reports USING btree "
       "  (date_trunc('hour'::text, timezone('UTC'::text, producer_timestamp)), producer_timestamp, certname)"]
      ["CREATE INDEX reports_cached_catalog_status_on_fail ON reports USING btree"
       "  (cached_catalog_status) WHERE (cached_catalog_status = 'on_failure'::text)"]
      "CREATE INDEX reports_catalog_uuid_idx ON reports USING btree (catalog_uuid)"
      "CREATE INDEX idx_reports_certname_end_time ON reports USING btree (certname,end_time)"
      "CREATE INDEX reports_end_time_idx ON reports USING btree (end_time)"
      "CREATE INDEX reports_environment_id_idx ON reports USING btree (environment_id)"

      ;; NOTE: The reports_hash_expr_idx changes to include
      ;; the producer_timestamp because declarative partitions must include the
      ;; range column in all unique indices.
      "CREATE UNIQUE INDEX reports_hash_expr_idx ON reports USING btree (encode(hash, 'hex'::text), producer_timestamp)"
      ;; The idx_reports_id index goes away since it's a duplicate of the reports_pkey

      "CREATE INDEX reports_job_id_idx ON reports USING btree (job_id) WHERE (job_id IS NOT NULL)"
      "CREATE INDEX reports_noop_idx ON reports USING btree (noop) WHERE (noop = true)"
      "CREATE INDEX reports_status_id_idx ON reports USING btree (status_id)"
      "CREATE INDEX reports_tx_uuid_expr_idx ON reports USING btree (((transaction_uuid)::text))")))

(defn require-previously-optional-trigram-indexes
  "Create trgm indexes if they do not currently exist."
  []
  (when-not (sutils/index-exists? "fact_paths_path_trgm")
    (log/info (trs "Creating additional index `fact_paths_path_trgm`"))
    (jdbc/do-commands
     "CREATE INDEX fact_paths_path_trgm ON fact_paths USING gist (path gist_trgm_ops)"))
  (when-not (sutils/index-exists? "packages_name_trgm")
    (log/info (trs "Creating additional index `packages_name_trgm`"))
    (jdbc/do-commands
     ["create index packages_name_trgm on packages"
      "  using gin (name gin_trgm_ops)"]))
  (when-not (sutils/index-exists? "catalog_resources_file_trgm")
    (log/info (trs "Creating additional index `catalog_resources_file_trgm`"))
    (jdbc/do-commands
     ["create index catalog_resources_file_trgm on catalog_resources"
      " using gin (file gin_trgm_ops) where file is not null"]
     "alter table catalog_resources set (autovacuum_analyze_scale_factor = 0.01)"))
  nil)

(defn remove-catalog-resources-file-trgm-index
  "Drops the expensive catalog_resources_file_trgm index, and resets the
  catalog_resources autovacuum_analyze_scale_factor storage parameter to
  default."
  []
  (jdbc/do-commands
    "DROP INDEX IF EXISTS catalog_resources_file_trgm"
    "ALTER TABLE catalog_resources RESET ( autovacuum_analyze_scale_factor )"))

(def migrations
  "The available migrations, as a map from migration version to migration function."
  {00 require-schema-migrations-table
   28 init-through-2-3-8
   29 version-2yz-to-300-migration
   30 add-expired-to-certnames
   31 coalesce-fact-values
   32 add-producer-timestamp-to-reports
   33 add-certname-id-to-certnames
   34 add-certname-id-to-resource-events
   ;; This dummy migration ensures that even databases that were up to
   ;; date when the analyze-tables code was added will still analyze
   ;; their existing databases.
   35 (fn [] true)
   36 rename-environments-name-to-environment
   37 add-jsonb-columns-for-metrics-and-logs
   38 add-code-id-to-catalogs
   39 add-expression-indexes-for-bytea-queries
   40 fix-bytea-expression-indexes-to-use-encode
   41 factset-hash-field-not-nullable
   42 add-support-for-historical-catalogs
   43 add-indexes-for-reports-summary-query
   44 add-catalog-uuid-to-reports-and-catalogs
   45 index-certnames-latest-report-id
   46 drop-certnames-latest-id-index
   47 add-producer-to-reports-catalogs-and-factsets
   48 add-noop-pending-to-reports
   49 add-corrective-change-columns
   50 remove-historical-catalogs
   51 fact-values-value-to-jsonb
   52 resource-params-cache-parameters-to-jsonb
   53 add-corrective-change-index
   54 drop-resource-events-resource-type-idx
   55 index-certnames-unique-latest-report-id
   56 merge-fact-values-into-facts
   57 add-package-tables
   58 add-gin-index-on-resource-params-cache
   59 improve-facts-factset-id-index
   60 fix-missing-edges-fk-constraint
   61 add-latest-report-timestamp-to-certnames
   62 reports-partial-indices
   63 add-job-id
   64 rededuplicate-facts
   65 varchar-columns-to-text
   66 jsonb-facts
   ;; migration 69 replaces migration 67 - we do not need to apply both
   ;; 67 exposed an agent bug where we would get duplicate resource events
   ;; from a failed exec call. The updated migration adds additional columns
   ;; to the hash to avoid these sorts of collisions
   67 (fn [])
   68 support-fact-expiration-configuration
   ;; replaced by reporting-partitioned-tables
   69 migration-69-stub
   70 migrate-md5-to-sha1-hashes
   71 autovacuum-vacuum-scale-factor-factsets-catalogs-certnames
   72 add-support-for-catalog-inputs
   73 resource-events-partitioning
   74 reports-partitioning
   75 add-report-type-to-reports
   76 add-report-partition-indexes-on-id
   77 add-catalog-inputs-pkey
   78 add-catalog-inputs-hash
   79 add-report-partition-indexes-on-certname-end-time
   80 add-workspaces-tables
   81 migrate-resource-events-to-declarative-partitioning
   82 migrate-reports-to-declarative-partitioning
   83 require-previously-optional-trigram-indexes
   84 remove-catalog-resources-file-trgm-index})
   ;; Make sure that if you change the structure of reports
   ;; or resource events, you also update the delete-reports
   ;; cli command.

(defn desired-schema-version
  "The newest migration this PuppetDB instance knows about.  Anything
  newer is considered invalid as far as this instance is concerned."
  []
  (apply max (keys migrations)))

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
           (apply <= 0 %)]}
  (jdbc/with-db-transaction []
    (if-not (->> ["select 1 from pg_catalog.pg_tables where tablename = 'schema_migrations'"]
                 jdbc/query-to-vec
                 seq)
      (sorted-set)
      (->> "SELECT version FROM schema_migrations ORDER BY version"
           query-to-vec
           (map :version)
           (apply sorted-set)))))

(defn pending-migrations
  "Returns a collection of pending migrations, ordered from oldest to latest."
  []
  {:post [(map? %)
          (sorted? %)
          (apply <= 0 (keys %))
          (<= (count %) (count migrations))]}
  (let [pending (set/difference (kitchensink/keyset migrations) (applied-migrations))]
    (into (sorted-map)
          (select-keys migrations pending))))

(defn unrecognized-migrations
  "Returns a set of migrations, likely created by a future version of
  PuppetDB"
  [applied-migrations]
  (set/difference applied-migrations
                  (set (range 0 (inc (desired-schema-version))))))

(defn require-valid-schema
  "Returns true if the database is ready for use, otherwise throws."
  []
  (let [applied-migration-versions (applied-migrations)
        latest-applied-migration (last applied-migration-versions)
        known-migrations (apply sorted-set (keys migrations))]

    (when (and latest-applied-migration
               (< latest-applied-migration (first (remove zero? known-migrations))))
      (throw (IllegalStateException.
              (str
               (trs "Found an old and unsupported database migration (migration number {0})." latest-applied-migration)
               " "
               (trs "PuppetDB only supports upgrading from the previous major version to the current major version.")
               " "
               (trs "As an example, users wanting to upgrade from 2.x to 4.x should first upgrade to 3.x.")))))

    (when-let [unexpected (-> (unrecognized-migrations applied-migration-versions)
                              sort first)]
      (throw (IllegalStateException.
              (trs "Your PuppetDB database contains a schema migration numbered {0}, but this version of PuppetDB does not recognize that version."
                   unexpected))))
    true))

(defn analyze-if-exists [tables]
  (let [exists?  (->> "select tablename from pg_catalog.pg_tables"
                      jdbc/query-to-vec
                      (map :tablename)
                      set)
        tables (filter exists? tables)]
    (log/info (trs "Updating table statistics for: {0}" (str/join ", " tables)))
    (apply jdbc/do-commands-outside-txn
           (map #(str "vacuum analyze " %) tables))))

(defn- analyze-tables
  [requested]
  {:pre [(or (nil? requested) (set? requested))]}
  ;; Always analyze these small tables since we had a good long period
  ;; where the post-migration analysis above wasn't actually working,
  ;; and since this shouldn't be expensive.
  (let [small-tables (set ["value_types" "report_statuses"])]
    (analyze-if-exists (set/union small-tables requested))))

(defn run-migrations
  "Runs the requested migrations.  Returns a set of tables that should
  be analyzed if there were any migrations."
  [migrations]
  (let [tables (->> migrations
                    (map (fn [[version migration]]
                           (log/info (trs "Applying database migration version {0}"
                                          version))
                           (let [t0 (now)
                                 result (migration)]
                             (record-migration! version)
                             (log/info (trs "Applied database migration version {0} in {1} ms"
                                            version (in-millis (interval t0 (now)))))
                             result)))
                    (filter map?)
                    (map ::vacuum-analyze)
                    (apply set/union))]
    (when-not (empty? tables)
      (log/info (trs "There are no pending migrations"))
      tables)))


(defn note-migrations-finished
  "Currently just a hook used during testing."
  []
  true)

(defn call-with-connections-blocked-during-migration
  ;; The users in the second arity is the original list of users,
  ;; which we need to track so we can do all the checks at the end.
  ;; Until the end, some users (roles) might still have indirect
  ;; connect privileges via the other users.  And of course we have to
  ;; use a full recursion rather than an internal loop recur because
  ;; clj can't recur across a try.
  ([db-name users f]
   (call-with-connections-blocked-during-migration db-name users users f))
  ([db-name [user & others] users f]
   (if-not user
     (do
       (log/info
        (trs "Disconnecting all non-migrator connections to {0} database before migrating"
             db-name))
       (doseq [user users]
         ;; Because the revoke may not actually produce an error when
         ;; it doesn't work.
         (when (jdbc/has-database-privilege? user db-name "connect")
           (throw
            (ex-info (str "Unable to prevent " (pr-str user)" connections during migration")
                     {:kind ::unable-to-block-other-pdbs-during-migration})))
         (jdbc/disconnect-db-role db-name user))
       (f))
     ;; FIXME: Of course the finally blocks here won't run if the jvm
     ;; doesn't quit in a way that lets threads finish (TK's shutdown
     ;; process does).  To accommodate that, make sure we
     ;; unconditionally restore the relevant privileges at startup if
     ;; possible.
     (do
       (log/info
        (trs "Revoking {0} database access from {1} during migrations"
             db-name user))
       (jdbc/revoke-role-db-access user db-name)
       (try!
        (call-with-connections-blocked-during-migration db-name others users f)
        (finally
          (try
            (log/info
             (trs "Restoring access to {0} database to {1} after migrating"
                  db-name user))
            (jdbc/restore-role-db-access user db-name)
            ;; FIXME: can/should we remove this now, since try! does suppress
            (catch Throwable ex
              ;; Don't let this rethrow, so that it won't suppress any
              ;; pending exception.
              (log/error
               ex (trs "Unable to restore " (pr-str user) " db access after migrations"))))
          (when-not (jdbc/has-database-privilege? user db-name "connect")
            (throw
             (ex-info (str "Unable to restore " (pr-str user) " connect rights after migration")
                      {:kind ::unable-to-block-other-pdbs-during-migration})))))))))

(defn- require-extensions []
  (when-not (sutils/pg-extension? "pg_trgm")
    (let [msg (str (trs "PuppetDB requires the PostgreSQL `pg_trgm` extension.\n")
                   (trs "Please connect to the PuppetDB database and run this:\n")
                   (trs "    CREATE EXTENSION pg_trgm;\n"))]
      (log/error msg)
      (throw (ex-info ""
                      {:kind :puppetlabs.trapperkeeper.core/exit
                       :status 2
                       :messages [[msg *err*]]})))))

(defn update-schema
  [write-user read-user db-name]
  {:pre [(or (and write-user read-user db-name)
             (not (or write-user read-user db-name)))]}
  ;; When a write user is given, assume we're connected to the
  ;; database as the migrator (e.g. via the PDBMigrationsPool), and
  ;; then do our part to attempt to prevent concurrent migrations or
  ;; any access to a database that's at an unexpected migration level.
  ;; See the connectionInitSql setting for related efforts.
  (let [orig-user (and write-user (jdbc/current-user))
        migrate #(jdbc/with-db-transaction []
                   (require-schema-migrations-table)
                   (log/info (trs "Locking migrations table before migrating"))
                   (jdbc/do-commands
                    "lock table schema_migrations in access exclusive mode")
                   (require-extensions)
                   (require-valid-schema)
                   (let [tables (run-migrations (pending-migrations))]
                     (note-migrations-finished)
                     tables))]
    (if-not write-user
      (migrate)
      (when-not (empty? (pending-migrations))
        (call-with-connections-blocked-during-migration
         db-name
         (distinct [read-user write-user])
         (fn []
           ;; So new tables, etc. are owned by the write-user
           (jdbc/do-commands (str "set role " (jdbc/double-quote write-user)))
           (try!
            (migrate)
            (finally
              (jdbc/do-commands (str "set role " (jdbc/double-quote orig-user)))))))))))

(defn initialize-schema
  "Ensures the database is migrated to the latest version, and returns
  true if and only if migrations were run.  Assumes the database
  status, version, etc. have already been validated."
  ([] (initialize-schema nil nil nil))
  ([write-user read-user db-name]
   (try
     (let [tables (update-schema write-user read-user db-name)]
       (analyze-tables tables)
       (some? (seq tables)))
     (catch java.sql.SQLException e
       (log/error e (trs "Caught SQLException during migration"))
       (loop [ex (.getNextException e)]
         (when ex
           (log/error ex (trs "Unraveled exception"))
           (recur (.getNextException ex))))
       (throw e)))))
