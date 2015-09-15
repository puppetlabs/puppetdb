(ns puppetlabs.puppetdb.jdbc
  "Database utilities"
  (:import (com.jolbox.bonecp BoneCPDataSource BoneCPConfig)
           (java.util.concurrent TimeUnit))
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]
            [puppetlabs.puppetdb.time :as pl-time]
            [puppetlabs.puppetdb.jdbc.internal :refer :all]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [clojure.math.numeric-tower :as math]))

(def ^:dynamic *db* nil)

(defn db []
  *db*)

(defmacro with-db-connection [spec & body]
  `(sql/with-db-connection [db# ~spec]
     (binding [*db* db#]
       ~@body)))

(defmacro with-db-transaction [opts & body]
  `(sql/with-db-transaction [db# (db) ~@opts]
     (binding [*db* db#]
       ~@body)))

(defn do-commands
  "Calls clojure.jdbc/db-do-commands after adding (jdbc/db) as the
  first argument."
  {:arglists '([sql-command & sql-commands]
               [transaction? sql-command & sql-commands])}
  [transaction? & commands]
  (apply sql/db-do-commands *db* transaction? commands))

(defn do-prepared
  "Calls clojure.jdbc/db-do-prepared after adding (jdbc/db) as the
  first argument."
  {:arglists '([sql & param-groups]
               [transaction? sql & param-groups])}
  [transaction? & remainder]
  (apply sql/db-do-prepared *db* transaction? remainder))

(defn insert!
  "Calls clojure.jdbc/insert! after adding (jdbc/db) as the first argument."
  {:arglists '([table row-map :transaction? true :entities identity]
               [table row-map & row-maps :transaction? true :entities identity]
               [table col-name-vec col-val-vec
                & col-val-vecs :transaction? true :entities identity])}
  [table & options]
  (apply sql/insert! *db* table options))

(defn update!
  "Calls clojure.jdbc/update! after adding (jdbc/db) as the first argument."
  {:arglists '([[table set-map where-clause
                 & {:keys [entities transaction?]
                    :or {entities identity transaction? true}}]])}
  [table set-map where-clause & options]
  (apply sql/update! *db* table set-map where-clause options))

(defn delete!
  "Calls clojure.jdbc/delete! after adding (jdbc/db) as the first argument."
  {:arglists '([table where-clause
                & {:keys [entities transaction?]
                   :or {entities identity transaction? true}}])}
  [table where-clause & options]
  (apply sql/delete! *db* table where-clause options))

(defn query
  "Calls clojure.jdbc/query after adding (jdbc/db) as the first argument."
  {:arglists '([sql-and-params
                :as-arrays? false :identifiers clojure.string/lower-case
                :result-set-fn doall :row-fn identity]
               [sql-and-params
                :as-arrays? true :identifiers clojure.string/lower-case
                :result-set-fn vec :row-fn identity]
               [[sql-string & params]]
               [[stmt & params]]
               [[option-map sql-string & params]])}
  [sql-params & remainder]
  (apply sql/query *db* sql-params remainder))

(defn query-with-resultset
  "Calls clojure.jdbc/db-query-with-resultset after adding (jdbc/db)
  as the first argument."
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
   (string? (first q))
   (every? (complement coll?) (rest q))))

(def valid-results-query-schema
  "Schema type for compiled query-eng queries"
  (s/pred
   #(and (map? %)
         (valid-jdbc-query? (:results-query %)))))

(defn convert-result-arrays
  "Converts Java and JDBC arrays in a result set using the provided function
  (eg. vec, set). Values which aren't arrays are unchanged."
  ([result-set]
     (convert-result-arrays vec result-set))
  ([f result-set]
     (let [convert #(cond
                     (kitchensink/array? %) (f %)
                     (isa? (class %) java.sql.Array) (f (.getArray %))
                     :else %)]
       (map #(kitchensink/mapvals convert %) result-set))))

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
    (query-with-resultset sql-query-and-params
                          #(-> (limit-result-set! limit (sql/result-set-seq %))
                               convert-result-arrays
                               vec))))

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

(pls/defn-validated exponential-sleep!
  "Sleeps for a period of time, based on an adjustable base exponential backoff.

   In most cases a base of 2 is sufficient, but you can adjust this to create
   tighter or looser sleep cycles."
  [current-attempt :- s/Int
   base :- (s/either s/Int Double)]
  (let [sleep-ms (-> (math/expt base current-attempt)
                     (- 1)
                     (* 1000))]
    (Thread/sleep sleep-ms)))

(pls/defn-validated retry-sql-or-fail :- Boolean
  "Log the attempts made, and the final failure during SQL retries.

   If there are still retries to perform, returns false."
  [remaining :- s/Int
   current :- s/Int
   exception]
  (cond
   (zero? remaining)
   (do
     (log/warn "Caught exception. Last attempt, throwing exception.")
     (throw exception))

   :else
   (do
     (log/debugf "Caught %s: '%s'. SQL Error code: '%s'. Attempt: %s of %s."
                 (.getName (class exception))
                 (.getMessage exception)
                 (.getSQLState exception)
                 (inc current)
                 (+ current remaining))
     (exponential-sleep! current 1.3)
     false)))

(pls/defn-validated retry-sql*
  "Invokes (f) up to n times, retrying only if a transient connection
  exception occurs.  The transient exceptions will be suppressed, and
  all others will be thrown.  If the final invocation results in a
  transient exception, it will also be thrown."
  [n :- s/Int
   f]
  (loop [r n
         current 0]
    (if-let [result (try
                      [(f)]
                      ;; Catch connection errors, and retry for some of them.
                      ;; cf. PostgreSQL docs: Appendix A. PostgreSQL Error Codes
                      (catch java.sql.SQLException e
                        ;; This includes org.postgresql.util.PSQLException
                        (let [sqlstate (.getSQLState e)]
                          (case sqlstate
                            ;; The connection does not exist
                            "08003" (retry-sql-or-fail r current e)
                            ;; PostgreSQL was restarted
                            "57P01" (retry-sql-or-fail r current e)
                            ;; All other errors are not retried
                            (throw e)))))]
      (result 0)
      (recur (dec r) (inc current)))))

(defmacro retry-sql
  "Executes body. If a retryable error state is thrown, will retry. At most n
   retries are done. If still some exception is thrown it is bubbled upwards in
   the call chain."
  [n & body]
  `(retry-sql* ~n (fn [] ~@body)))

(defn with-transacted-connection-fn
  "Calls f within a transaction with the specified clojure.jdbc
  isolation level.  If isolation is nil :read-committed will be used.
  Retries the transaction up to 5 times."
  [db-spec isolation f]
  (retry-sql 5
             (with-db-connection db-spec
               (with-db-transaction [:isolation (or isolation :read-committed)]
                 (f)))))

(defmacro with-transacted-connection'
  "Executes the body within a transaction with the specified clojure.jdbc
  isolation level.  If isolation is nil :read-committed will be used.
  Retries the transaction up to 5 times."
  [db-spec tx-isolation-level & body]
  `(with-transacted-connection-fn ~db-spec ~tx-isolation-level
     (fn []
       ~@body)))

(defmacro with-transacted-connection
  "Executes the body within a transaction with a clojure.jdbc
  isolation level of :read-committed.  Retries the transaction up to 5
  times."
  [db-spec & body]
  `(with-transacted-connection-fn ~db-spec nil
     (fn []
       ~@body)))

(defn with-query-results-cursor
  "Executes the given parameterized query within a transaction,
  producing a lazy sequence of rows. The callback `func` is executed
  on the entire sequence.

  The lazy sequence is backed by an active database cursor, and is thus
  useful for streaming very large resultsets.

  The cursor is closed when `func` returns. If an exception is thrown,
  the query is cancelled."
  [[sql & params] func]
  (with-db-transaction []
   (with-open [stmt (.prepareStatement (:connection *db*) sql)]
     (doseq [[index value] (map-indexed vector params)]
       (.setObject stmt (inc index) value))
     (.setFetchSize stmt 500)
     (with-open [rset (.executeQuery stmt)]
       (try
         (-> rset
             sql/result-set-seq
             convert-result-arrays
             func)
         (catch Exception e
           ;; Cancel the current query
           (.cancel stmt)
           (throw e)))))))

(defn make-connection-pool
  "Create a new database connection pool"
  [{:keys [classname subprotocol subname user username password
           partition-conn-min partition-conn-max partition-count
           stats log-statements log-slow-statements statements-cache-size
           conn-max-age conn-lifetime conn-keep-alive read-only?
           connection-timeout pool-availability-threshold]
    :as db}]
  (let [;; Load the database driver class explicitly, to avoid jar load ordering
        ;; issues.
        _ (Class/forName classname)
        log-slow-statements-duration (pl-time/to-seconds log-slow-statements)
        config          (doto (new BoneCPConfig)
                          (.setDefaultAutoCommit false)
                          (.setLazyInit true)
                          (.setMinConnectionsPerPartition partition-conn-min)
                          (.setMaxConnectionsPerPartition partition-conn-max)
                          (.setPartitionCount partition-count)
                          (.setConnectionTestStatement "begin; select 1; commit;")
                          (.setStatisticsEnabled stats)
                          (.setIdleMaxAgeInMinutes (pl-time/to-minutes conn-max-age))
                          (.setIdleConnectionTestPeriodInMinutes (pl-time/to-minutes conn-keep-alive))
                          ;; paste the URL back together from parts.
                          (.setJdbcUrl (str "jdbc:" subprotocol ":" subname))
                          (.setConnectionHook (connection-hook log-statements log-slow-statements-duration))
                          (.setStatementsCacheSize statements-cache-size)
                          (.setDefaultReadOnly read-only?)
                          (.setConnectionTimeoutInMs connection-timeout))
        user (or user username)]
    ;; configurable without default
    (some->> user str (.setUsername config))
    (some->> password str (.setPassword config))
    (some->> pool-availability-threshold (.setPoolAvailabilityThreshold config))
    (some->> log-statements (.setLogStatementsEnabled config))
    (when conn-lifetime (.setMaxConnectionAge config (pl-time/to-minutes conn-lifetime) TimeUnit/MINUTES))

    (.setQueryExecuteTimeLimit config log-slow-statements-duration (TimeUnit/SECONDS))
    ;; ...aaand, create the pool.
    (BoneCPDataSource. config)))

(defn pooled-datasource
  "Given a database connection attribute map, return a JDBC datasource
  compatible with clojure.java.jdbc that is backed by a connection
  pool."
  [options]
  {:datasource (make-connection-pool options)})

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
