(ns puppetlabs.puppetdb.scf.migrate
  "Schema migrations

   The `migrate!` function can be used to apply all the pending migrations to
   the database, in ascending order of schema version. Pending is defined as
   having a schema version greater than the current version in the database.

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
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.utils :refer [flush-and-exit]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.time :refer [in-millis interval now to-timestamp]]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [org.postgresql.util PGobject]))

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

    ;; schema_migrations table
    "CREATE TABLE schema_migrations (
        version integer NOT NULL,
        \"time\" timestamp without time zone NOT NULL)"

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

    "ALTER TABLE ONLY schema_migrations
        ADD CONSTRAINT schema_migrations_pkey PRIMARY KEY (version)"

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
  (jdbc/do-commands
   "UPDATE factsets SET hash=md5(factsets.id::text)::bytea WHERE hash is NULL"
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
  []
  (jdbc/do-commands
   "CREATE FUNCTION dual_md5(BYTEA, BYTEA) RETURNS bytea AS $$
      BEGIN
        RETURN digest($1 || $2, 'md5');
      END;
    $$ LANGUAGE plpgsql"
   "CREATE AGGREGATE md5_agg (BYTEA)
    (
      sfunc = dual_md5,
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
  []
  (jdbc/do-commands
    "alter table catalogs drop column edges"
    "alter table catalogs drop column resources"
    "delete from catalogs where id not in (select catalog_id from latest_catalogs)"
    "drop table latest_catalogs"))

(defn migrate-through-app
  [table1 table2 column-list munge-fn]
  (let [columns (string/join "," column-list)]
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

(def migrations
  "The available migrations, as a map from migration version to migration function."
  {28 init-through-2-3-8
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
   66 jsonb-facts})

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
      (let [message (.getMessage e)
            sql-state (.getSQLState e)]
        (if (and (or (= sql-state "42P01") ; postgresql: undefined_table
                     (= sql-state "42501")) ; hsqldb: user lacks privilege or object not found
                 (re-find #"(?i)schema_migrations" message))
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

(defn run-migrations
  [db-connection-pool]
  (let [applied-migration-versions (applied-migrations)
        latest-applied-migration (last applied-migration-versions)
        known-migrations (apply sorted-set (keys migrations))
        small-tables (set ["value_types" "report_statuses"])
        analyze (fn [tables]
                  (let [exists?  (->> "select tablename from pg_catalog.pg_tables"
                                      jdbc/query-to-vec
                                      (map :tablename)
                                      set)
                        tables (filter exists? tables)]
                    (log/info (trs "Updating table statistics for: {0}"
                                   (str/join ", " tables)))
                    (apply jdbc/do-commands-outside-txn
                           (map #(str "vacuum analyze " %) tables))))]

    (when (and latest-applied-migration
               (< latest-applied-migration (first known-migrations)))
      (throw (IllegalStateException.
              (str
               (trs "Found an old and unuspported database migration (migration number {0})." latest-applied-migration)
               " "
               (trs "PuppetDB only supports upgrading from the previous major version to the current major version.")
               " "
               (trs "As an example, users wanting to upgrade from 2.x to 4.x should first upgrade to 3.x.")))))

    (when-let [unexpected (first (unrecognized-migrations applied-migration-versions known-migrations))]
      (throw (IllegalStateException.
              (trs "Your PuppetDB database contains a schema migration numbered {0}, but this version of PuppetDB does not recognize that version."
                   unexpected))))

    (if-let [pending (seq (pending-migrations))]
      (let [tables (jdbc/with-db-transaction []
                     (->> pending
                          (map (fn [[version migration]]
                                 (log/info (trs "Applying database migration version {0}" version))
                                 (let [t0 (now)]
                                   (let [result (migration)]
                                     (record-migration! version)
                                     (log/info (trs "Applied database migration version {0} in {1} ms"
                                                    version (in-millis (interval t0 (now)))))
                                     result))))
                          (filter map?)
                          (map ::vacuum-analyze)
                          (apply set/union small-tables)))]
        (analyze tables)
        true)
      (do
        (log/info (trs "There are no pending migrations"))
        false))))

(defn migrate! [db-connection-pool]
  "Migrates database to the latest schema version. Does nothing if
  database is already at the latest schema version.  Requires a
  connection pool because some operations may require an indepdendent
  database connection.  Returns true if there were any migrations."
  (try
    (run-migrations db-connection-pool)
    (catch java.sql.SQLException e
      (log/error e (trs "Caught SQLException during migration"))
      (loop [ex (.getNextException e)]
        (when ex
          (log/error ex (trs "Unraveled exception"))
          (recur (.getNextException ex))))
      (throw e))))

;; SPECIAL INDEX HANDLING

(defn trgm-indexes!
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
      "  using gin (name gin_trgm_ops)"])))

(defn ensure-report-id-index []
  (when-not (sutils/index-exists? "idx_reports_compound_id")
    (log/info "Indexing reports for id queries")

    (jdbc/do-commands
     "create index idx_reports_compound_id on reports
        (producer_timestamp, certname, hash)
        where start_time is not null")

    (jdbc/do-commands-outside-txn
     "vacuum analyze reports")))

(defn indexes!
  "Create missing indexes for applicable database platforms."
  [config]
  (jdbc/with-db-transaction []
    (if (sutils/pg-extension? "pg_trgm")
      (trgm-indexes!)
      (log/warn
       (str
        (trs "Missing PostgreSQL extension `pg_trgm`")
        "\n\n"
        (trs "We are unable to create the recommended pg_trgm indexes due to\nthe extension not being installed correctly.")
        " "
        (trs " Run the command:\n\n    CREATE EXTENSION pg_trgm;\n\nas the database super user on the PuppetDB database to correct\nthis, then restart PuppetDB.\n")))))
  (ensure-report-id-index))
