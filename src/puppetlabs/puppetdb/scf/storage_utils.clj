(ns puppetlabs.puppetdb.scf.storage-utils
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [honeysql.core :as hcore]
            [honeysql.format :as hfmt]
            [clojure.string :as str]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s])
  (:import [org.postgresql.util PGobject]))

;; SCHEMA

(defn array-to-param
  [col-type java-type values]
  (.createArrayOf (:connection (jdbc/db))
                  col-type
                  (into-array java-type values)))

(def pg-extension-map
  "Maps to the table definition in postgres, but only includes some of the
   columns:

     Table pg_catalog.pg_extension
   Column     |  Type   | Modifiers
   ----------------+---------+-----------
   extname        | name    | not null
   extrelocatable | boolean | not null
   extversion     | text    |"
  {:name s/Str
   :relocatable s/Bool
   :version (s/maybe s/Str)})

(def db-version
  "A list containing a major and minor version for the database"
  [s/Int])

;; FUNCTIONS
(defn db-metadata-fn
  []
  (let [db-metadata (.getMetaData (:connection (jdbc/db)))]
    {:database (.getDatabaseProductName db-metadata)
     :version [(.getDatabaseMajorVersion db-metadata)
               (.getDatabaseMinorVersion db-metadata)]}))

(def db-metadata
  (delay (db-metadata-fn)))

(pls/defn-validated db-version? :- s/Bool
  "Returns true if the version list you pass matches the version of the current
   database."
  [version :- db-version]
  (= (:version @db-metadata) version))

(pls/defn-validated db-version-older-than? :- s/Bool
  "Returns true if the current database version is older than the version list
   you pass it."
  [version :- db-version]
  (neg? (compare (:version @db-metadata) version)))

(pls/defn-validated db-version-newer-than? :- s/Bool
  "Returns true if the current database version is newer than the version list
   you pass it."
  [version :- db-version]
  (pos? (compare (:version @db-metadata) version)))

(defn sql-current-connection-table-names
  "Returns the names of all of the tables in the public schema of the
  current connection's database.  This is most useful for debugging /
  testing purposes to allow introspection on the database.  (Some of
  our unit tests rely on this.)."
  []
  (let [query   "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public'"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map :table_name results)))

(defn sql-current-connection-sequence-names
  "Returns the names of all of the sequences in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query   "SELECT sequence_name FROM information_schema.sequences WHERE LOWER(sequence_schema) = 'public'"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map :sequence_name results)))

(defn sql-current-connection-function-names
  "Returns the names of all of the functions in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query (str "SELECT pp.proname as name, pg_catalog.pg_get_function_arguments(pp.oid) as args "
                   "FROM pg_proc pp "
                   "INNER JOIN pg_namespace pn ON (pp.pronamespace = pn.oid) "
                   "INNER JOIN pg_language pl ON (pp.prolang = pl.oid) "
                   "WHERE pl.lanname NOT IN ('c') "
                   "AND pn.nspname NOT LIKE 'pg_%'"
                   "AND pn.nspname <> 'information_schema'")
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map (fn [{:keys [name args]}] (str name "(" args ")"))
         results)))

(pls/defn-validated pg-installed-extensions :- {s/Str pg-extension-map}
  "Obtain the extensions installed and metadata about each extension for
   the current database."
  []
  (let [query "SELECT extname as name,
                      extversion as version,
                      extrelocatable as relocatable
               FROM pg_extension"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (zipmap (map :name results)
            results)))

(pls/defn-validated pg-extension? :- s/Bool
  "Returns true if the named PostgreSQL extension is installed."
  [extension :- s/Str]
  (let [extensions (pg-installed-extensions)]
    (not= (get extensions extension) nil)))

(defn current-schema
  "Returns the current schema of the database connection on postgres."
  []
  (->> (jdbc/query-to-vec "select current_schema")
       first
       :current_schema))

(pls/defn-validated index-exists? :- s/Bool
  "Returns true if the index exists. Only supported on PostgreSQL currently."
  ([index :- s/Str]
   (let [schema (current-schema)]
     (index-exists? index schema)))
  ([index :- s/Str
    namespace :- s/Str]
     (let [query "SELECT c.relname
                    FROM   pg_index as idx
                    JOIN   pg_class as c ON c.oid = idx.indexrelid
                    JOIN   pg_namespace as ns ON ns.oid = c.relnamespace
                    WHERE  ns.nspname = ?
                      AND  c.relname = ?"
           results (jdbc/query-to-vec [query namespace index])]
       (= (:relname (first results))
          index))))

(defn to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  [coll]
  (->> coll
       (into-array Object)
       (.createArrayOf (:connection (jdbc/db)) "varchar")))

(defn legacy-sql-regexp-match
  "Returns the SQL for performing a regexp match."
  [column]
  (format "(%s ~ ? AND %s IS NOT NULL)" column column))

(defn sql-as-numeric
  "Returns the SQL for converting the given column to a number, or to
  NULL if it is not numeric."
  [column]
  (hcore/raw (format (str "CASE WHEN %s~E'^\\\\d+$' THEN %s::bigint "
                          "WHEN %s~E'^\\\\d+\\\\.\\\\d+$' THEN %s::float "
                          "ELSE NULL END")
                     column column column column)))

(defn sql-array-type-string
  "Returns the SQL to declare an array of the supplied base database
  type."
  [basetype]
  (format "%s ARRAY[1]" basetype))

(defn sql-array-query-string
  "Returns an SQL fragment representing a query for a single value
  being found in an array column in the database.

    (str \"SELECT ... WHERE \" (sql-array-query-string \"column_name\"))

  The returned SQL fragment will contain *one* parameter placeholder,
  which must be supplied as the value to be matched."
  [column]
  (hcore/raw
   (format "ARRAY[?::text] <@ %s" (name column))))

(defn sql-regexp-match
  "Returns db code for performing a regexp match."
  [column]
  [:and
   [(keyword "~") column "?"]
   [:is-not column nil]])

(defn sql-regexp-array-match
  "Returns SQL for performing a regexp match against the contents of
  an array. If any of the array's items match the supplied regexp,
  then that satisfies the match."
  [column]
  (hcore/raw
   (format "EXISTS(SELECT 1 FROM UNNEST(%s) WHERE UNNEST ~ ?)" (name column))))

(defn sql-in-array
  [column]
  (hcore/raw
   (format "%s = ANY(?)" (first (hfmt/format column)))))

(defn db-serialize
  "Serialize `value` into a form appropriate for querying against a
  serialized database column."
  [value]
  (json/generate-string (kitchensink/sort-nested-maps value)))

(defn fix-identity-sequence
  "Resets a sequence to the maximum value used in a column. Useful when a
  sequence gets out of sync due to a bug or after a transfer."
  [table column]
  {:pre [(string? table)
         (string? column)]}
  (jdbc/with-db-transaction []
    (jdbc/do-commands (str "LOCK TABLE " table " IN ACCESS EXCLUSIVE MODE"))
    (jdbc/query-with-resultset
     [(format
       "select setval(pg_get_serial_sequence(?, ?), (select max(%s) from %s))"
       column table)
      table column]
     (constantly nil))))

(defn sql-hash-as-str
  [column]
  (format "encode(%s::bytea, 'hex')" column))

(defn vacuum-analyze
  [db]
  (sql/with-db-connection [conn db]
    (sql/execute! db ["vacuum analyze"] :transaction? false)))

(defn parse-db-hash
  [db-hash]
  (clojure.string/replace (.getValue db-hash) "\\x" ""))

(defn parse-db-uuid
  [db-uuid]
  (.toString db-uuid))

(pls/defn-validated parse-db-json
  "Produce a function for parsing an object stored as json."
  [db-json :- (s/maybe (s/cond-pre s/Str PGobject))]
  (some-> db-json .getValue (json/parse-string true)))

(pls/defn-validated str->pgobject :- PGobject
  [type :- s/Str
   value]
  (doto (PGobject.)
    (.setType type)
    (.setValue value)))

(defn munge-uuid-for-storage
  [value]
  (str->pgobject "uuid" value))

(defn bytea-escape [s]
  (format "\\x%s" s))

(defn munge-hash-for-storage
  [hash]
  (str->pgobject "bytea" (bytea-escape hash)))

(defn munge-json-for-storage
  "Prepare a clojure object for storage depending on db type."
  [value]
  (let [json-str (json/generate-string value)]
    (str->pgobject "json" json-str)))

(defn munge-jsonb-for-storage
  "Prepare a clojure object for storage depending on db type."
  [value]
  (let [json-str (json/generate-string value)]
    (str->pgobject "jsonb" json-str)))

(defn db-up?
  [db-spec]
  (utils/with-timeout 1000 false
    (try
      (jdbc/with-transacted-connection db-spec
        (let [select-42 "SELECT (a - b) AS answer FROM (VALUES ((7 * 7), 7)) AS x(a, b)"
              [{:keys [answer]}] (jdbc/query [select-42])]
          (= answer 42)))
      (catch Exception _
        false))))

(defn analyze-small-tables
  [small-tables]
  (log/info "Analyzing small tables")
  (apply jdbc/do-commands-outside-txn
         (map #(str "analyze " %) small-tables)))
