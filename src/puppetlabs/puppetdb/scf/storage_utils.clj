(ns puppetlabs.puppetdb.scf.storage-utils
  (:require [clojure.java.jdbc :as sql]
            [honeysql.core :as hcore]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :as pls]
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

(pls/defn-validated postgres? :- s/Bool
  "Returns true if currently connected to a Postgres DB instance"
  []
  (= (:database @db-metadata) "PostgreSQL"))

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

(pls/defn-validated pg-installed-extensions :- {s/Str pg-extension-map}
  "Obtain the extensions installed and metadata about each extension for
   the current database."
  []
  {:pre [(postgres?)]}
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
  (->> coll
       (into-array Object)
       (.createArrayOf (:connection (jdbc/db)) "varchar")))

(defmulti sql-array-type-string
  "Returns a string representing the correct way to declare an array
  of the supplied base database type."
  ;; Dispatch based on database from the metadata of DB connection at the time
  ;; of call; this copes gracefully with multiple connection types.
  (fn [_] (:database @db-metadata)))

(defmulti sql-array-query-string
  "Returns an SQL fragment representing a query for a single value being
found in an array column in the database.

  `(str \"SELECT ... WHERE \" (sql-array-query-string \"column_name\"))`

The returned SQL fragment will contain *one* parameter placeholder, which
must be supplied as the value to be matched."
  (fn [column] (:database @db-metadata)))

(defmulti legacy-sql-regexp-match
  "Returns db-specific code for performing a regexp match"
  (fn [_] (:database @db-metadata)))

(defmethod legacy-sql-regexp-match "PostgreSQL"
  [column]
  (format "(%s ~ ? AND %s IS NOT NULL)" column column))

(defmethod legacy-sql-regexp-match "HSQL Database Engine"
  [column]
  (format "REGEXP_SUBSTRING(%s, ?) IS NOT NULL" column))

(defmulti sql-regexp-match
  "Returns db-specific code for performing a regexp match"
  (fn [_] (:database @db-metadata)))

(defmulti sql-regexp-array-match
  "Returns db-specific code for performing a regexp match against the
  contents of an array. If any of the array's items match the supplied
  regexp, then that satisfies the match."
  (fn [_ _ _] (:database @db-metadata)))

(defmulti sql-as-numeric
  "Returns appropriate db-specific code for converting the given column to a
  number, or to NULL if it is not numeric."
  (fn [_] (:database @db-metadata)))

(defmethod sql-as-numeric "PostgreSQL"
  [column]
  (hcore/raw (format (str "CASE WHEN %s~E'^\\\\d+$' THEN %s::bigint "
                          "WHEN %s~E'^\\\\d+\\\\.\\\\d+$' THEN %s::float "
                          "ELSE NULL END")
                     column column column column)))

(defmethod sql-as-numeric "HSQL Database Engine"
  [column]
  (hcore/raw (format (str "CASE WHEN REGEXP_MATCHES(%s, '^\\d+$') THEN CAST(%s AS BIGINT) "
                          "WHEN REGEXP_MATCHES(%s, '^\\d+\\.\\d+$') THEN CAST(%s AS FLOAT) "
                          "ELSE NULL END")
                     column column column column)))

(defmethod sql-array-type-string "PostgreSQL"
  [basetype]
  (format "%s ARRAY[1]" basetype))

(defmethod sql-array-type-string "HSQL Database Engine"
  [basetype]
  (format "%s ARRAY[%d]" basetype 65535))

(defmethod sql-array-query-string "PostgreSQL"
  [column]
  (hcore/raw
   (format "ARRAY[?::text] <@ %s" (name column))))

(defmethod sql-array-query-string "HSQL Database Engine"
  [column]
  (hcore/raw
   (format "? IN (UNNEST(%s))" (name column))))

(defmethod sql-regexp-match "PostgreSQL"
  [column]
  [:and
   [(keyword "~") column "?"]
   [:is-not column nil]])

(defmethod sql-regexp-match "HSQL Database Engine"
  [column]
  [:is-not (h/regexp-substring column "?") nil])

(defmethod sql-regexp-array-match "PostgreSQL"
  [orig-table _ column]
  (hcore/raw
   (format "EXISTS(SELECT 1 FROM UNNEST(%s) WHERE UNNEST ~ ?)" (name column))))

(defmethod sql-regexp-array-match "HSQL Database Engine"
  [orig-table query-table column]
  ;; What evil have I wrought upon the land? Good gravy.
  ;;
  ;; This is entirely due to the fact that HSQLDB doesn't support the
  ;; UNNEST operator referencing a column from an outer table. UNNEST
  ;; *has* to come after the parent table in the FROM clause of a
  ;; separate SQL statement.
  (let [col (name column)]
    (hcore/raw
     (format (str "EXISTS(SELECT 1 FROM %s %s_copy, UNNEST(%s) AS T(the_tag) "
                  "WHERE %s.%s=%s_copy.%s AND REGEXP_SUBSTRING(the_tag, ?) IS NOT NULL)")
             orig-table orig-table col query-table col orig-table col))))

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
   (if (postgres?)
     ;; PostgreSQL specific way
     (do
       (jdbc/do-commands (str "LOCK TABLE " table " IN ACCESS EXCLUSIVE MODE"))
       (jdbc/query-with-resultset
        [(str "SELECT setval(pg_get_serial_sequence(?, ?),
                             (SELECT max(" column ") FROM " table "))")
         table column]
        (constantly nil)))

     ;; HSQLDB specific way
     (let [_ (jdbc/do-commands (str "LOCK TABLE " table " WRITE"))
           maxid (jdbc/query-with-resultset
                  [(str "SELECT max(" column ") as id FROM " table)]
                  (comp :id first sql/result-set-seq))
           ;; While postgres handles a nil case gracefully, hsqldb does not
           ;; so here we return 1 if the maxid is nil, and otherwise return
           ;; maxid +1 to indicate that the next number should be higher
           ;; then the current one.
           restartid (if (nil? maxid) 1 (inc maxid))]
       (jdbc/do-commands (str "ALTER TABLE " table " ALTER COLUMN " column
                              " RESTART WITH " restartid))))))

(defn sql-hash-as-str
  [column]
  (if (postgres?)
    (format "trim(leading '\\x' from %s::text)" column)
    column))

(defn sql-uuid-as-str
  [column]
  (if (postgres?)
    (format "%s::text" column)
    column))

(defn parse-db-hash
  [db-hash]
  (if (postgres?)
    (clojure.string/replace (.getValue db-hash) "\\x" "")
    db-hash))

(defn parse-db-uuid
  [db-uuid]
  (if (postgres?)
    (.toString db-uuid)
    db-uuid))

(pls/defn-validated parse-db-json
  "Produce a function for parsing an object stored as json."
  [db-json :- (s/maybe (s/either s/Str PGobject))]
  (if-let [json (if (postgres?)
                  (when db-json (.getValue db-json))
                  db-json)]
    (json/parse-string json true)))

(pls/defn-validated str->pgobject :- PGobject
  [type :- s/Str
   value]
  (doto (PGobject.)
    (.setType type)
    (.setValue value)))

(defn munge-uuid-for-storage
  [value]
  (if (postgres?)
    (str->pgobject "uuid" value)
    value))

(defn munge-hash-for-storage
  [hash]
  (if (postgres?)
    (str->pgobject "bytea" (format "\\x%s" hash))
    hash))

(defn munge-json-for-storage
  "Prepare a clojure object for storage depending on db type."
  [value]
  (let [json-str (json/generate-string value)]
    (if (postgres?)
      (str->pgobject "json" json-str)
      json-str)))

(defn munge-jsonb-for-storage
  "Prepare a clojure object for storage depending on db type."
  [value]
  (let [json-str (json/generate-string value)]
    (if (postgres?)
      (str->pgobject "jsonb" json-str)
      json-str)))

