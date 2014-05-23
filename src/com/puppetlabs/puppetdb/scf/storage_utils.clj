(ns com.puppetlabs.puppetdb.scf.storage-utils
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.tools.logging :as log]))

(defn sql-current-connection-database-name
  "Return the database product name currently in use."
  []
  (.. (sql/find-connection)
      (getMetaData)
      (getDatabaseProductName)))

(defn sql-current-connection-database-version
  "Return the version of the database product currently in use."
  []
  {:post [(every? integer? %)
          (= (count %) 2)]}
  (let [db-metadata (.. (sql/find-connection)
                      (getMetaData))
        major (.getDatabaseMajorVersion db-metadata)
        minor (.getDatabaseMinorVersion db-metadata)]
    [major minor]))

(defn postgres?
  "Returns true if currently connected to a Postgres DB instance"
  []
  (= (sql-current-connection-database-name) "PostgreSQL"))

(defn pg-older-than-8-4?
  "Returns true if connected to a Postgres instance that is newer than 8.1"
  []
  (and (postgres?)
       (neg? (compare (sql-current-connection-database-version) [8 4]))))

(defn pg-8-4?
  "Returns true if connected to a Postgres instance that is newer than 8.1"
  []
  (and (postgres?)
       (= (sql-current-connection-database-version) [8 4])))

(defn pg-newer-than-8-4?
  "Returns true if connected to a Postgres instance that is newer than 8.1"
  []
  (and (postgres?)
       (pos? (compare (sql-current-connection-database-version) [8 4]))))

(defn sql-current-connection-table-names
  "Return all of the table names that are present in the database based on the
  current connection.  This is most useful for debugging / testing  purposes
  to allow introspection on the database.  (Some of our unit tests rely on this.)"
  []
  (let [query   "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public'"
        results (sql/transaction (jdbc/query-to-vec query))]
    (map :table_name results)))

(defn to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  [coll]
  (let [connection (sql/find-connection)]
    (->> coll
         (into-array Object)
         (.createArrayOf connection "varchar"))))

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
  (format (str "CASE WHEN %s~E'^\\\\d+$' THEN %s::integer "
               "WHEN %s~E'^\\\\d+\\\\.\\\\d+$' THEN %s::float "
               "ELSE NULL END")
          column column column column))

(defmethod sql-as-numeric "HSQL Database Engine"
  [column]
  (format (str "CASE WHEN REGEXP_MATCHES(%s, '^\\d+$') THEN CAST(%s AS INTEGER) "
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
  (format "%s ~ ?" column))

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
