(ns com.puppetlabs.puppetdb.scf.storage-utils
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

;; SCHEMA

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

(defn sql-current-connection-database-name
  "Return the database product name currently in use."
  []
  (.. (sql/find-connection)
      (getMetaData)
      (getDatabaseProductName)))

(pls/defn-validated sql-current-connection-database-version :- db-version
  "Return the version of the database product currently in use."
  []
  (let [db-metadata (.. (sql/find-connection)
                      (getMetaData))
        major (.getDatabaseMajorVersion db-metadata)
        minor (.getDatabaseMinorVersion db-metadata)]
    [major minor]))

(pls/defn-validated postgres? :- s/Bool
  "Returns true if currently connected to a Postgres DB instance"
  []
  (= (sql-current-connection-database-name) "PostgreSQL"))

(pls/defn-validated db-version? :- s/Bool
  "Returns true if the version list you pass matches the version of the current
   database."
  [version :- db-version]
  (= (sql-current-connection-database-version) version))

(pls/defn-validated db-version-older-than? :- s/Bool
  "Returns true if the current database version is older than the version list
   you pass it."
  [version :- db-version]
  (neg? (compare (sql-current-connection-database-version) version)))

(pls/defn-validated db-version-newer-than? :- s/Bool
  "Returns true if the current database version is newer than the version list
   you pass it."
  [version :- db-version]
  (pos? (compare (sql-current-connection-database-version) version)))

(defn sql-current-connection-table-names
  "Returns the names of all of the tables in the public schema of the
  current connection's database.  This is most useful for debugging /
  testing purposes to allow introspection on the database.  (Some of
  our unit tests rely on this.)."
  []
  (let [query   "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public'"
        results (sql/transaction (jdbc/query-to-vec query))]
    (map :table_name results)))

(defn sql-current-connection-sequence-names
  "Returns the names of all of the sequences in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query   "SELECT sequence_name FROM information_schema.sequences WHERE LOWER(sequence_schema) = 'public'"
        results (sql/transaction (jdbc/query-to-vec query))]
    (map :sequence_name results)))

(pls/defn-validated pg-installed-extensions :- {s/Str pg-extension-map}
  "Obtain the extensions installed and metadata about each extension for
   the current database."
  []
  {:pre [(postgres?)]}
  (let [query "SELECT extname as name,
                      extversion as version,
                      extrelocatable as relocatable
               FROM pg_extension"
        results (sql/transaction (jdbc/query-to-vec query))]
    (zipmap (map :name results)
            results)))

(pls/defn-validated pg-extension? :- s/Bool
  "Returns true if the named PostgreSQL extension is installed."
  [extension :- s/Str]
  {:pre [(postgres?)]}
  (let [extensions (pg-installed-extensions)]
    (not= (get extensions extension) nil)))

(pls/defn-validated index-exists? :- s/Bool
  "Returns true if the index exists. Only supported on PostgreSQL currently."
  ([index :- s/Str]
     (index-exists? index "public"))
  ([index :- s/Str
    namespace :- s/Str]
     {:pre [(postgres?)]}
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
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object)
         (.createArrayOf connection "varchar"))))

(defn array-to-param
  [col-type java-type values]
  (.createArrayOf (sql/connection)
                  col-type
                  (into-array java-type values)))

(defmulti sql-array-type-string
  "Returns a string representing the correct way to declare an array
  of the supplied base database type."
  ;; Dispatch based on database from the metadata of DB connection at the time
  ;; of call; this copes gracefully with multiple connection types.
  (fn [_] (sql-current-connection-database-name)))

(defmulti sql-array-query-string
  "Returns an SQL fragment representing a query for a single value being
found in an array column in the database.

  `(str \"SELECT ... WHERE \" (sql-array-query-string \"column_name\"))`

The returned SQL fragment will contain *one* parameter placeholder, which
must be supplied as the value to be matched."
  (fn [column] (sql-current-connection-database-name)))

(defmulti sql-regexp-match
  "Returns db-specific code for performing a regexp match"
  (fn [_] (sql-current-connection-database-name)))

(defmulti sql-regexp-array-match
  "Returns db-specific code for performing a regexp match against the
  contents of an array. If any of the array's items match the supplied
  regexp, then that satisfies the match."
  (fn [_ _ _] (sql-current-connection-database-name)))

(defmulti sql-as-numeric
  "Returns appropriate db-specific code for converting the given column to a
  number, or to NULL if it is not numeric."
  (fn [_] (sql-current-connection-database-name)))

(defmethod sql-as-numeric "PostgreSQL"
  [column]
  (format (str "CASE WHEN %s~E'^\\\\d+$' THEN %s::bigint "
               "WHEN %s~E'^\\\\d+\\\\.\\\\d+$' THEN %s::float "
               "ELSE NULL END")
          column column column column))

(defmethod sql-as-numeric "HSQL Database Engine"
  [column]
  (format (str "CASE WHEN REGEXP_MATCHES(%s, '^\\d+$') THEN CAST(%s AS BIGINT) "
               "WHEN REGEXP_MATCHES(%s, '^\\d+\\.\\d+$') THEN CAST(%s AS FLOAT) "
               "ELSE NULL END")
          column column column column))

(defmethod sql-array-type-string "PostgreSQL"
  [basetype]
  (format "%s ARRAY[1]" basetype))

(defmethod sql-array-type-string "HSQL Database Engine"
  [basetype]
  (format "%s ARRAY[%d]" basetype 65535))

(defmethod sql-array-query-string "PostgreSQL"
  [column]
  (format "ARRAY[?::text] <@ %s" column))

(defmethod sql-array-query-string "HSQL Database Engine"
  [column]
  (format "? IN (UNNEST(%s))" column))

(defmethod sql-regexp-match "PostgreSQL"
  [column]
  (format "(%s ~ ? AND %s IS NOT NULL)" column column))

(defmethod sql-regexp-match "HSQL Database Engine"
  [column]
  (format "REGEXP_SUBSTRING(%s, ?) IS NOT NULL" column))

(defmethod sql-regexp-array-match "PostgreSQL"
  [orig-table query-table column]
  (format "EXISTS(SELECT 1 FROM UNNEST(%s) WHERE UNNEST ~ ?)" column))

(defmethod sql-regexp-array-match "HSQL Database Engine"
  [orig-table query-table column]
  ;; What evil have I wrought upon the land? Good gravy.
  ;;
  ;; This is entirely due to the fact that HSQLDB doesn't support the
  ;; UNNEST operator referencing a column from an outer table. UNNEST
  ;; *has* to come after the parent table in the FROM clause of a
  ;; separate SQL statement.
  (format (str "EXISTS(SELECT 1 FROM %s %s_copy, UNNEST(%s) AS T(the_tag) "
               "WHERE %s.%s=%s_copy.%s AND REGEXP_SUBSTRING(the_tag, ?) IS NOT NULL)")
          orig-table orig-table column query-table column orig-table column))

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
  (sql/transaction
    (if (postgres?)
      ;; PostgreSQL specific way
      (do
        (sql/do-commands (str "LOCK TABLE " table " IN ACCESS EXCLUSIVE MODE"))
        (sql/with-query-results _
          [(str "SELECT setval(
            pg_get_serial_sequence(?, ?),
            (SELECT max(" column ") FROM " table "))") table column]))

      ;; HSQLDB specific way
      (let [_ (sql/do-commands (str "LOCK TABLE " table " WRITE"))
            maxid (sql/with-query-results result-set
                      [(str "SELECT max(" column ") as id FROM " table)]
                      (:id (first result-set)))
            ;; While postgres handles a nil case gracefully, hsqldb does not
            ;; so here we return 1 if the maxid is nil, and otherwise return
            ;; maxid +1 to indicate that the next number should be higher
            ;; then the current one.
            restartid (if (nil? maxid) 1 (inc maxid))]
        (sql/do-commands
          (str "ALTER TABLE " table " ALTER COLUMN " column
            " RESTART WITH " restartid))))))
