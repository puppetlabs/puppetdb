(ns puppetlabs.puppetdb.jdbc
  "Database utilities"
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]
            [puppetlabs.puppetdb.time :as pl-time]
            [puppetlabs.puppetdb.jdbc.internal :refer [limit-result-set!]]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs]])
  (:import
   (com.zaxxer.hikari HikariDataSource HikariConfig)
   (java.sql Connection SQLException SQLTransientConnectionException)
   (org.postgresql.util PGobject)))

(def ^:dynamic *db* nil)

(defn db []
  *db*)

(defn sql-state [kw-name]
  (or ({:invalid-regular-expression "2201B"
        :program-limit-exceeded "54000"
        :lock-not-available "55P03"
        :query-canceled "57014"}
       kw-name)
      (throw (IllegalArgumentException.
              (trs "Requested unknown SQL state")))))

(defmacro with-db-connection [spec & body]
  `(sql/with-db-connection [db# ~spec]
     (binding [*db* db#]
       ~@body)))

(defmacro with-monitored-db-connection [spec status & body]
  `(let [thread# (Thread/currentThread)
         status# ~status]
     (locking status#
       (swap! status# assoc :thread thread# :connecting? true))
     (try
       (sql/with-db-connection [db# ~spec]
         (locking status#
           (swap! status# dissoc :connecting?))
         (binding [*db* db#]
           ~@body))
       (finally
         (locking status#
           (swap! status# dissoc :thread :connecting?))))))

(defmacro with-db-transaction [opts & body]
  `(sql/with-db-transaction [db# (db) (hash-map ~@opts)]
     (binding [*db* db#]
       ~@body)))

(defn do-commands
  "Runs the given commands in a transaction on the database given
  by (jdbc/db).  If a command is a collection, converts it to a string
  via (clojure.string/join command)."
  [& commands]
  (sql/db-do-commands *db* true
                      (mapv #(if (coll? %) (string/join %) %)
                            commands)))

(defn do-prepared
  "Executes an optionally parametrized sql expression in a transaction
  on the database given by (jdbc/db).  If a command is a collection,
  converts it to a string via (clojure.string/join command)."
  [sql & params]
  (sql/db-do-prepared *db* true
                      (apply vector
                             (if (coll? sql) (string/join sql) sql)
                             params)
                      {:multi? true}))

(defn do-commands-outside-txn [& commands]
  (let [^Connection conn (:connection *db*)
        orig (.getAutoCommit conn)]
    (.setAutoCommit conn true)
    (try
      (doseq [c commands]
        (with-open [s (.createStatement conn)]
          (.execute s c)))
      (finally (.setAutoCommit conn orig)))))

(defn insert!
  "Inserts a single row in either map form or lists of columns & values form. The
  database to use is given by (jdbc/db). Returns a one-element sequence with the
  inserted row as returned by the database."
  ([table row]
   (sql/insert! *db* table row {}))
  ([table columns values]
   (sql/insert! *db* table columns values {})))

;; STOLEN: clojure JDBC functions modified to support an ON CONFLICT clause

(defn- insert-multi-row
  "Given a table and a list of columns, followed by a list of column
  value sequences, return a vector of the SQL needed for the insert
  followed by the list of column value sequences. The entities
  function specifies how column names are transformed."
  [table columns values {:keys [on-conflict] :as opts}]
  (let [nc (count columns)
        vcs (map count values)]
    (if (not (and (or (zero? nc) (= nc (first vcs))) (apply = vcs)))
      (throw (IllegalArgumentException.
              "insert! called with inconsistent number of columns / values"))
      (into [(str "INSERT INTO " (name table)
                  (when (seq columns)
                    (str " ( "
                         (str/join ", " (map (fn [col] (name col)) columns))
                         " )"))
                  " VALUES ( "
                  (str/join ", " (repeat (first vcs) "?"))
                  " ) "
                  (when on-conflict
                    (str "on conflict " on-conflict)))]
            values))))

(defn- insert-single-row-on-conflict
  "Given a table and a map representing a row, return a vector of the
  SQL needed for the insert followed by the list of column values. The
  entities function specifies how column names are transformed."
  [table row entities {:keys [on-conflict] :as opts}]
  (let [ks (keys row)]
    (into [(str "INSERT INTO " (name table) " ( "
                (str/join ", " (map (fn [col] (name col)) ks))
                " ) VALUES ( "
                (str/join ", " (repeat (count ks) "?"))
                " ) "
                (when on-conflict
                  (str "ON CONFLICT " on-conflict)))]
          (vals row))))

(defn- insert-cols!
  "Given a database connection, a table name, a sequence of columns
  names, a sequence of vectors of column values, one per row, and an
  options map, insert the rows into the database."
  [db table cols values opts]
  (let [{:keys [entities transaction?]} (merge {:entities identity :transaction? true}
                                               (when (map? db) db)
                                               opts)
        sql-params (insert-multi-row table cols values opts)]
    (if-let [con (sql/db-find-connection db)]
      (sql/db-do-prepared db transaction? sql-params {:multi? true})
      (with-open [con (sql/get-connection db)]
        (sql/db-do-prepared (sql/add-connection db con) transaction?
                            sql-params {:multi? true})))))

(defn- multi-insert-helper
  "Given a (connected) database connection and some SQL
  statements (for multiple inserts), run a prepared statement on each
  and return any generated keys.  Note: we are eager so an unrealized
  lazy-seq cannot escape from the connection."
  [db stmts opts]
  (doall (map (fn [row] (sql/db-do-prepared-return-keys db false row opts))
              stmts)))

(defn- insert-helper
  "Given a (connected) database connection, a transaction flag and some SQL statements
  (for one or more inserts), run a prepared statement or a sequence of
  them."
  [db transaction? stmts opts]
  (if transaction?
    (sql/with-db-transaction [t-db db] (multi-insert-helper t-db stmts opts))
    (multi-insert-helper db stmts opts)))

(defn- insert-rows!
  "Given a database connection, a table name, a sequence of rows, and
  an options map, insert the rows into the database."
  [db table rows {:keys [on-conflict] :as opts}]
  (let [{:keys [entities identifiers qualifier transaction?]}
        (merge {:entities identity :identifiers str/lower-case :transaction? true}
               (when (map? db) db)
               opts)
        sql-params (map (fn [row]
                          (when-not (map? row)
                            (throw (IllegalArgumentException.
                                    "insert / insert-multi! called with a non-map row")))
                          (insert-single-row-on-conflict table row entities opts))
                        rows)]
    (if-let [con (sql/db-find-connection db)]
      (insert-helper db transaction? sql-params
                     {:identifiers identifiers :qualifier qualifier})
      (with-open [con (sql/get-connection db)]
        (insert-helper (sql/add-connection db con) transaction? sql-params
                       {:identifiers identifiers :qualifier qualifier})))))

;; END STOLEN FUNCTIONS

(defn insert-multi!
  "Inserts multiple rows in either map form or lists of columns &
  values form. The database to use is given by (jdbc/db). Returns a
  sequence with every inserted row as returned by the database."
  ;; since clojure.java.jdbc will open a connection even when given an empty
  ;; rows or values sequence, bypass it here if either of those are empty
  ([table rows]
   (sql/insert-multi! *db* table rows))
  ([table cols-or-rows values-or-opts]
   (if (map? values-or-opts)
     (insert-rows! *db* table cols-or-rows values-or-opts)
     (insert-cols! *db* table cols-or-rows values-or-opts {})))
  ([table cols values opts]
   (sql/insert-multi! *db* table cols values opts)))

(defn update!
  "Calls clojure.jdbc/update! after adding (jdbc/db) as the first argument."
  [table set-map where-clause]
  (sql/update! *db* table set-map where-clause {}))

(defn delete!
  "Calls clojure.jdbc/delete! after adding (jdbc/db) as the first argument."
  [table where-clause]
  (sql/delete! *db* table where-clause {}))

(defn query
  "Calls clojure.jdbc/query after adding (jdbc/db) as the first argument."
  [sql-params & remainder]
  (apply sql/query *db* sql-params remainder))

(defn query-with-resultset
  "Calls clojure.jdbc/db-query-with-resultset after adding (jdbc/db)
   as the first argument. Note that this will hold the whole resultset in memory
   due to the default jdbc fetchsize of 0. If streaming is required, use
   call-with-query-rows."
  {:arglists '([[sql-string & params] func]
               [[stmt & params] func]
               [[options-map sql-string & params] func])}
  [sql-params func]
  (sql/db-query-with-resultset *db* sql-params func))

(defn valid-jdbc-query?
  "Most SQL queries generated in the PuppetDB code base are represented internally
  as a vector whose first item is the SQL string (with optional '?' placeholders),
  and whose remaining items (if any) are simple data types that can be passed
  to a JDBC prepared statement as parameter values to bind to the placeholders
  in the SQL string.  This function validates that a form complies to this structure.
  It is intended primarily for use in pre- and post-conditions, for validation."
  [q]
  (and
   (vector? q)
   (string? (first q))))

(def valid-results-query-schema
  "Schema type for compiled query-eng queries"
  (s/pred
   #(and (map? %)
         (valid-jdbc-query? (:results-query %)))))

(defn sql-array? [x]
  (instance? java.sql.Array x))

(defn convert-any-sql-array [x convert]
  (if-not (sql-array? x)
    x
    (convert (.getArray ^java.sql.Array x))))

(defn any-sql-array->vec [x]
  (convert-any-sql-array x vec))

(defn call-with-query-rows
  "Calls (f rows), where rows is a lazy sequence of rows generated
  from within a transaction.  The sequence is backed by an active
  database cursor which will be closed when f returns.  Cancels the
  query if f throws an exception.  The option names that correspond to
  jdbc/result-set-seq options will affect the rows produced as they do
  for that function.  So for example, when as-arrays? is logically
  true, the result rows will be vectors, not maps, and the first
  result row will be a vector of column names."
  ([query f] (call-with-query-rows query {} f))
  ([[sql & params]
    {:keys [as-arrays? identifiers qualifier read-columns fetch-size] :as opts}
    f]
   (with-db-transaction []
     (with-open [stmt (.prepareStatement ^Connection (:connection *db*) sql)]
       (doseq [[i param] (map vector (range) params)]
         (.setObject stmt (inc i) param))
       (.setFetchSize stmt (or fetch-size 500))
       (with-open [rset (.executeQuery stmt)]
         (try
           (f (sql/result-set-seq rset opts))
           (catch Exception e
             ;; Cancel the current query
             (.cancel stmt)
             (throw e))))))))


(defn ^:deprecated call-with-array-converted-query-rows
  "Calls (f rows), where rows is a lazy sequence of rows generated
  from within a transaction.  Converts any java.sql.Array
  or (.isArray (class v)) values to a vector.  The sequence is backed
  by an active database cursor which will be closed when f returns.
  Cancels the query if f throws an exception.  The option names that
  correspond to jdbc/result-set-seq options will affect the rows
  produced as they do for that function.  So for example, when
  as-arrays? is logically true, the result rows will be vectors, not
  maps, and the first result row will be a vector of column names.
  Note that this function is deprecated.  Please prefer
  call-with-query-rows, and apply any necessary conversions directly
  to each row.  (Most columns cannot be arrays.)"
  ([query f] (call-with-array-converted-query-rows query {} f))
  ([[sql & params]
    {:keys [as-arrays? identifiers qualifier read-columns] :as opts}
    f]
   (with-db-transaction []
     (with-open [stmt (.prepareStatement ^Connection (:connection *db*) sql)]
       (doseq [[i param] (map vector (range) params)]
         (.setObject stmt (inc i) param))
       (.setFetchSize stmt 500)
       (let [fix-vals (if as-arrays?
                        #(mapv any-sql-array->vec %)
                        #(kitchensink/mapvals any-sql-array->vec %))]
         (with-open [rset (.executeQuery stmt)]
           (try
             (f (map fix-vals (sql/result-set-seq rset opts)))
             (catch Exception e
               ;; Cancel the current query
               (.cancel stmt)
               (throw e)))))))))

(defn limited-query-to-vec
  "Take a limit and an SQL query (with optional parameters), and return the
  result of the query as a vector.  These results, unlike a normal query result,
  are not tied to the database connection and can be safely returned.

  A value of `0` for `limit` is interpreted as 'no limit'.  For any other value,
  the function raises an error if the query would return more than `limit`
  results.

  Can be invoked in two ways: either passing the limit and the SQL query string,
  or the limit and a vector of the query string and parameters.

  (limited-query-to-vec 1000 \"select * from table\")
  (limited-query-to-vec 1000 [\"select * from table where column = ?\" 12])"
  [limit query]
  {:pre  [(and (integer? limit) (>= limit 0))
          ((some-fn string? vector?) query)]
   :post [(or (zero? limit) (<= (count %) limit))]}
  (let [sql-query-and-params (if (string? query) [query] query)]
    (call-with-query-rows
     sql-query-and-params
     (fn [rows]
       (mapv #(kitchensink/mapvals any-sql-array->vec %)
             (limit-result-set! limit rows))))))

(defn query-to-vec
  "Take an SQL query and parameters, and return the result of the
  query as a vector.  These results, unlike a normal query result, are
  not tied to the database connection and can be safely returned.

  Can be invoked in three ways: either passing the SQL query string,
  or a vector of the query string and substitutions, or you can pass
  multiple parameters inline.

    (query-to-vec \"select * from table\")
    (query-to-vec [\"select * from table where column = ?\" 12])
    (query-to-vec \"select * from table where column = ?\" 12)"
  ([sql-query & params]
     (query-to-vec (vec (concat [sql-query] params))))
  ([sql-query-and-params]
     {:pre [((some-fn string? vector?) sql-query-and-params)]}
     (limited-query-to-vec 0 sql-query-and-params)))

(defn order-by-term->sql
  "Given a list of legal result columns and a map containing a single order_by term,
  return the SQL string representing this term for use in an ORDER BY clause."
  [[field order]]
  {:pre [(keyword? field)
         (contains? #{:ascending :descending} order)]
   :post [(string? %)]}
  (str (name field)
       (when (= order :descending) " DESC")))

(defn escape-single-quotes
  "Quote a string for SQL single quotes"
  [s]
  (str/replace s "'" "''"))

(defn single-quote
  "Given a string quote with single quotes and do proper SQL string escaping."
  [s]
  (str "'" (escape-single-quotes s) "'"))

(defn escape-double-quotes
  "Quote a string for SQL double quotes"
  [s]
  (str/replace s "\"" "\"\""))

(defn double-quote
  "Given a string quote with double quotes and do proper SQL string escaping."
  [s]
  (str "\"" (escape-double-quotes s) "\""))

(defn string->text-array-literal
  "Escape string s for inclusion in a postgres text[] literal,
  e.g. \"foo\\\"bar\" becomes the \"foo\\\\\\\"bar\" in
  '{\"foo\\\\\\\"bar\"}'"
  ;; https://www.postgresql.org/docs/11/arrays.html#ARRAYS-INPUT
  ;; https://www.postgresql.org/docs/11/sql-syntax-lexical.html#SQL-SYNTAX-CONSTANTS
  [s]
  (assert (string? s))
  (str \" (str/replace s "\\" "\\\\") \"))

(defn str-vec->array-literal
  "Returns a properly quoted sql values literal representing strvecs
  as a row set of text[], e.g. [\"x\" ...] -> (array['x', ...])."
  [strvec]
  (str "(array[" (str/join ", " (map single-quote strvec)) "])"))

(defn strs->db-array
  [strs]
  (assert (every? string? strs))
  ;; https://www.postgresql.org/docs/11/arrays.html#ARRAYS-INPUT
  (let [quoted (map string->text-array-literal strs)]
    (doto (PGobject.)
      (.setType "text[]")
      (.setValue (str \{ (str/join \, quoted) \})))))

;; Q: move/replace?
(defn create-json-path-extraction
  "Given a base json field and a path of keys to traverse, construct the proper
  SQL query of the form base->'key'->'key2'..."
  [field path]
   (str field
        (when (seq path)
          (->> (map single-quote path)
               (str/join "->")
               ;; prefix for the first arrow in field->'key1'...
               (str "->")))))

(pls/defn-validated paged-sql :- String
  "Given a sql string and a map of paging options, return a modified SQL string
  that contains the necessary LIMIT/OFFSET/ORDER BY clauses.  The map of paging
  options can contain any of the following keys:

  * :limit  (int)
  * :offset (int)
  * :order_by (array of maps; each map is an order_by term, consisting of
      required key :field and optional key :order.  Legal values for :order
      include 'asc' or 'desc'.)

  Note that if no paging options are specified, the original SQL will be
  returned completely unmodified."
  [sql :- String
   {:keys [limit offset order_by]}]
  {:pre [((some-fn nil? integer?) limit)
         ((some-fn nil? integer?) offset)
         ((some-fn nil? sequential?) order_by)
         (every? kitchensink/order-by-expr? order_by)]}
  (str sql
       (when-let [order-by (seq (map order-by-term->sql order_by))]
         (str " ORDER BY " (string/join ", " order-by)))
       (when limit (str " LIMIT " limit))
       (when offset (str " OFFSET " offset))))

(pls/defn-validated count-sql :- String
  "Takes a sql string and returns a modified sql string that will select
  the count of results that would be returned by the original sql."
  [sql :- String]
  (format "SELECT COUNT(*) AS result_count FROM (%s) results_to_count" sql))

(pls/defn-validated get-result-count :- s/Num
  "Takes a sql string, executes a `COUNT` statement against the database,
  and returns the number of results that the original query would have returned."
  [[count-sql & params]]
  {:pre [(string? count-sql)]}
  (-> (apply vector count-sql params)
      query-to-vec
      first
      :result_count))

(defn table-count
  "Returns the number of rows in the supplied table"
  [table]
  (-> "SELECT COUNT(*) as c FROM %s"
      (format table)
      query-to-vec
      first
      :c))

(defn retry-sql
  "Tries (f) up to max-attempts times, ignoring \"transient\"
  exceptions (e.g. connection exceptions).  When a
  cancellation-timeout is provided, also ignores cancelation
  exceptions (e.g. a statement_timeout).  Throws *any* exception from
  the final attempt."
  [max-attempts cancellation-timeout f]
  (let [canceled (sql-state :query-canceled)
        attempt #(try
                   [(f)]
                   (catch SQLTransientConnectionException ex
                     ex)
                   (catch SQLException ex
                     (when-not cancellation-timeout
                       (throw ex))
                     ;; canceled should include statement_timeout
                     (when-not (= canceled (.getSQLState ex))
                       (throw ex))
                     ex))]
    (loop [result (attempt)
           i 1]
      (cond
        (vector? result) (result 0)
        (>= i max-attempts)
        (do
          (log/warn (trs "Caught exception. Last attempt, throwing exception."))
          (throw result))
        :else
        (let [ex result]
          (assert (instance? SQLException ex))
          (if (= canceled (.getSQLState ex))
            (log/debug (trs "Timed out after {1}ms. Attempt: {2} of {3}."
                            cancellation-timeout i max-attempts))
            (log/debug (trs "Caught {0}: ''{1}''. SQL Error code: ''{2}''. Attempt: {3} of {4}."
                            (.getName (class ex)) (.getMessage ex) (.getSQLState ex)
                            i max-attempts)))
          ;; i.e. approx sleeps of: 0 300 690 1197 1856 ...
          (Thread/sleep (* (dec (Math/pow 1.3 (dec i)))
                           1000))
          (recur (attempt) (inc i)))))))

(defn with-transacted-connection-fn
  "Calls f within a transaction with the specified clojure.jdbc isolation level.
  If isolation is nil, the connection pool default (read committed) is used.
  Retries the transaction up to 5 times."
  [db-spec isolation f]
  ;; Avoid unnecessary round-trips, when the isolation is the
  ;; connection pool default.
  (let [isolation (when-not (= :read-committed isolation) isolation)]
    (retry-sql 5 nil #(with-db-connection db-spec
                        (with-db-transaction [:isolation isolation]
                          (f))))))

(defn retry-with-monitored-connection
  [db-spec status {:keys [isolation statement-timeout]} f]
  ;; Avoid unnecessary round-trips, when the isolation is the
  ;; connection pool default.
  (let [isolation (when-not (= :read-committed isolation) isolation)]
    (retry-sql 5
               statement-timeout
               #(with-monitored-db-connection db-spec status
                  (with-db-transaction [:isolation isolation]
                    (some->> statement-timeout
                             (format "set local statement_timeout = %d")
                             (sql/execute! *db*))
                    (f))))))

(defmacro with-transacted-connection'
  "Executes the body within a transaction with the specified clojure.jdbc
  isolation level. If isolation is nil, the connection pool default (read
  committed) is used. Retries the transaction up to 5 times."
  [db-spec tx-isolation-level & body]
  `(with-transacted-connection-fn ~db-spec ~tx-isolation-level
     (fn []
       ~@body)))

(defmacro with-transacted-connection
  "Executes the body within a transaction with isolation level read-committed.
  Retries the transaction up to 5 times."
  [db-spec & body]
  `(with-transacted-connection-fn ~db-spec nil
     (fn []
       ~@body)))

(defn ^:dynamic enable-jmx
  "This function exists to enable starting multiple PuppetDB instances
  inside a single JVM. Starting up a second instance results in a
  collision exception between JMX beans from the two
  instances. Disabling JMX from the broker avoids that issue"
  [^HikariConfig config metrics-registry]
  (.setMetricRegistry config metrics-registry))

(defn block-on-schema-mismatch
  "Compares the schema version the local PDB knows about against the version
  in the database. If these versions don't match it indicates that either a
  migration has been applied by another PDB or that the local PDB has been
  upgraded before the needed migration has been applied. Raising an exception
  here will cause all connection attempts from Hikari to fail until the local
  PDB has been upgraded or a needed migration is applied"
  [expected-schema]
  {:pre [(integer? expected-schema)]}
  (format
   (str/join
    " "
    ["do $$"
     "declare"
     "  db_schema_version integer;"
     "  expected_max integer = %d;"
     "begin"
     "  if exists (select from information_schema.tables"
     "               where table_schema = 'public'"
     "                 and table_name = 'schema_migrations')"
     "  then"
     "    select max(version) into db_schema_version from schema_migrations;"
     "    case"
     "      when db_schema_version > expected_max then"
     "        raise exception"
     "          'Please upgrade PuppetDB: your database contains schema
                 migration %% which is too new for this version of PuppetDB.',
                 db_schema_version;"
     "      when db_schema_version < expected_max then"
     "        raise exception"
     "          'Please run PuppetDB with the migrate option set to true
                 to upgrade your database. The detected migration level %% is
                 out of date.', db_schema_version;"
     "      else"
     "        perform true;"  ; if schema versions match do nothing
     "    end case;"
     "  end if;"
     "end;"
     "$$ language plpgsql;"])
   expected-schema))

(defn make-connection-pool
  "Given a DB spec map containing :subprotocol, :subname, :user, and :password
  keys, return a pooled DB spec map (one containing just the :datasource key
  with a pooled DataSource object as the value). The returned pooled DB spec
  can be passed directly as the first argument to clojure.java.jdbc's
  functions."
  ([db-spec] (make-connection-pool db-spec nil))
  ([{:keys [subprotocol subname
            user username password
            connection-timeout
            conn-max-age
            conn-lifetime
            read-only?
            pool-name
            maximum-pool-size
            expected-schema
            rewrite-batched-inserts]
     :as db-spec}
    metrics-registry]
   (let [conn-lifetime-ms (some-> conn-max-age pl-time/to-millis)
         conn-max-age-ms (some-> conn-lifetime pl-time/to-millis)
         config (HikariConfig.)]
     (doto config
       (.setJdbcUrl (str "jdbc:" subprotocol ":" subname))
       (.setAutoCommit false)
       (.setInitializationFailTimeout -1)
       (.setTransactionIsolation "TRANSACTION_READ_COMMITTED")
       ;; Because we currently disable autocommit and specify
       ;; connectionInitSql, we need to set IsolateInternalQueries to
       ;; true because otherwise hikaricp will run our connection init
       ;; code which issues a select without committing it, which
       ;; causes later attempts to (for example) change the isolation
       ;; level to fail (because the pending transaction already
       ;; includes that select).
       (.setIsolateInternalQueries true))
     (->> ["DO $$ BEGIN"
           "IF CAST((SELECT setting FROM pg_settings WHERE name = 'server_version_num') AS INTEGER) >= 110000 THEN"
           "SET SESSION jit = off;"
           "END IF;"
           "END $$;"
           (when expected-schema
             (block-on-schema-mismatch expected-schema))]
          (str/join " ")
          (.setConnectionInitSql config))
     (when rewrite-batched-inserts
       (.setProperty (.getDataSourceProperties config) "reWriteBatchedInserts" rewrite-batched-inserts))
     (some->> pool-name (.setPoolName config))
     (some->> connection-timeout (.setConnectionTimeout config))
     (some->> maximum-pool-size (.setMaximumPoolSize config))
     (when (and conn-max-age-ms conn-lifetime-ms (> conn-max-age-ms conn-lifetime-ms))
       (some->> conn-max-age-ms (.setIdleTimeout config)))
     (some->> conn-lifetime-ms (.setMaxLifetime config))
     (some->> read-only? (.setReadOnly config))
     (some->> (or user username) str (.setUsername config))
     (some->> password str (.setPassword config))
     (some->> metrics-registry (enable-jmx config))
     (HikariDataSource. config))))

(defn pooled-datasource
  "Given a database connection attribute map, return a JDBC datasource
  compatible with clojure.java.jdbc that is backed by a connection
  pool."
  ([options] (pooled-datasource options nil))
  ([options metrics-registry]
   {:datasource (make-connection-pool options metrics-registry)}))

(defn in-clause
  "Create a prepared statement in clause, with a ? for every item in coll"
  [coll]
  {:pre [(seq coll)]}
  (str "in ("
       (str/join "," (repeat (count coll) "?"))
       ")"))

(defn in-clause-multi
  "Create a prepared statement in clause, with a `width`-sized series of ? for
  every item in coll."
  [coll width]
  {:pre [(seq coll)
         (integer? width)]}
  (let [inner (str "(" (str/join "," (repeat width "?")) ")")]
    (str "in ("
         (str/join "," (repeat (count coll) inner))
         ")")))

(defn current-user []
  (-> (query-to-vec "select user as user") first :user))

(defn current-database []
  (-> "select current_database();" query-to-vec first :current_database))

(defn-validated has-database-privilege? :- s/Bool
  [user db privilege]
  (-> ["select has_database_privilege(?, ?, ?)" user db privilege]
      query-to-vec first :has_database_privilege))

(defn-validated has-role? :- s/Bool
  [user role privilege]
  (-> ["select pg_has_role(?, ?, ?)" user role privilege]
      query-to-vec first :pg_has_role))

(defn disconnect-db
  "Forcibly disconnects all connections to the named db.  Requires
  that the current DB session has sufficient authorization."
  [db]
  (query-to-vec
   (str "select pg_terminate_backend (pg_stat_activity.pid)"
        "  from pg_stat_activity"
        "  where pg_stat_activity.datname = ?"
        "    and pid <> pg_backend_pid()")
   db))

(defn disconnect-db-role
  "Forcibly disconnects all connections from the specified role to the
  named db.  Requires that the current DB session has sufficient
  authorization."
  [db user]
  (query-to-vec
   (str "select pg_terminate_backend (pg_stat_activity.pid)"
        "  from pg_stat_activity"
        "  where pg_stat_activity.datname = ?"
        "    and pg_stat_activity.usename = ?"
        "    and pid <> pg_backend_pid()")
   db user))

(defn revoke-role-db-access
  [role db]
  ;; revoke commands can't be parameterized with the pgjdbc driver right now
  (do-commands
   (format "revoke connect on database %s from %s restrict"
           (double-quote db) (double-quote role))))

(defn restore-role-db-access
  [role db]
  ;; grant commands can't be parameterized with the pgjdbc driver right now
  (do-commands
   (format "grant connect on database %s to %s"
          (double-quote db) (double-quote role))))
