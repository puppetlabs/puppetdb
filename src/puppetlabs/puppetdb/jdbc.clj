(ns puppetlabs.puppetdb.jdbc
  "Database utilities"
  (:import (com.jolbox.bonecp BoneCPDataSource BoneCPConfig)
           (java.util.concurrent TimeUnit))
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.jdbc.internal :as jint]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]
            [puppetlabs.puppetdb.time :as pl-time]
            [puppetlabs.puppetdb.jdbc.internal :refer :all]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [clojure.math.numeric-tower :as math]))

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

;; ## String operations

(defn dashes->underscores
  "Accepts a string or a keyword as an argument, replaces all occurrences of the
  dash/hyphen character with an underscore, and returns the same type (string
  or keyword) that was passed in.  This is useful for translating data structures
  from their wire format to the format that is needed for JDBC."
  [str]
  (let [result (string/replace (name str) \- \_)]
    (if (keyword? str)
      (keyword result)
      result)))

(defn underscores->dashes
  "Accepts a string or a keyword as an argument, replaces all occurrences of the
  underscore character with a dash, and returns the same type (string
  or keyword) that was passed in.  This is useful for translating data structures
  from their JDBC-compatible representation to their wire format representation."
  [str]
  (let [result (string/replace (name str) \_ \-)]
    (if (keyword? str)
      (keyword result)
      result)))

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
    (sql/with-query-results result-set
      sql-query-and-params
      (let [limited-result-set (limit-result-set! limit result-set)]
        (-> limited-result-set
            (convert-result-arrays)
            (vec))))))

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
         (re-find #"^[\w\-]+$" (name field))
         (contains? #{:ascending :descending} order)]
   :post [(string? %)]}
  (let [field (dashes->underscores (name field))]
    (format "%s%s"
            field
            (if (= order :descending) " DESC" ""))))

(defn order-by->sql
  "Given a list of legal result columns an array of maps (where each map is
  an order_by term), return the SQL string representing the ORDER BY clause
  for the specified terms"
  [order-by]
  {:pre [((some-fn nil? sequential?) order-by)
         (every? kitchensink/order-by-expr? order-by)]
   :post [(string? %)]}
  (if (empty? order-by)
    ""
    (format " ORDER BY %s"
            (string/join ", "
                         (map order-by-term->sql order-by)))))

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
  (let [limit-clause     (if limit (format " LIMIT %s" limit) "")
        offset-clause    (if offset (format " OFFSET %s" offset) "")
        order-by-clause  (order-by->sql order_by)]
    (format "SELECT paged_results.* FROM (%s) paged_results%s%s%s"
            sql order-by-clause limit-clause offset-clause)))

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
      (query-to-vec)
      (first)
      :c))

(def ^{:doc "A more clojurey way to refer to the JDBC transaction isolation levels"}
  isolation-levels
  {:read-committed java.sql.Connection/TRANSACTION_READ_COMMITTED
   :repeatable-read java.sql.Connection/TRANSACTION_REPEATABLE_READ
   :serializable java.sql.Connection/TRANSACTION_SERIALIZABLE})

(pls/defn-validated exponential-sleep!
  "Sleeps for a period of time, based on an adjustable base exponential backoff.

   In most cases a base of 2 is sufficient, but you can adjust this to create
   tighter or looser sleep cycles."
  [current-attempt :- s/Int
   base :- (s/either s/Int Double)]
  (let [sleep-ms (* (- (math/expt base current-attempt) 1) 1000)]
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
     (log/debug (format "Caught %s: '%s'. SQL Error code: '%s'. Attempt: %s of %s."
                        (.getName (class exception))
                        (.getMessage exception)
                        (.getSQLState exception)
                        (inc current)
                        (+ current remaining)))
     (exponential-sleep! current 1.3)
     false)))

(pls/defn-validated retry-sql*
  "Executes f. If an exception is thrown, will retry. At most n retries
   are done. If still some retryable error state is thrown it is bubbled upwards
   in the call chain."
  [remaining :- s/Int
   f]
  (loop [r remaining
         current 0]
    (if-let [result (try
                      [(f)]

                      ;; This includes org.postgresql.util.PSQLException
                      (catch java.sql.SQLException e
                        (let [sqlstate (.getSQLState e)]
                          (case sqlstate
                            ;; Catch connection errors and retry them
                            "08003" (retry-sql-or-fail r current e)

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
  "Function for creating a connection that has the specified isolation
   level.  If one is not specified, the JDBC default will be used (read-committed)"
  [db-spec tx-isolation-level f]
  {:pre [(or (nil? tx-isolation-level)
             (get isolation-levels tx-isolation-level))]}
  (retry-sql 5
             (sql/with-connection db-spec
               (when-let [isolation-level (get isolation-levels tx-isolation-level)]
                 (.setTransactionIsolation (:connection jint/*db*) isolation-level))
               (sql/transaction (f)))))

(defmacro with-transacted-connection'
  "Like `clojure.java.jdbc/with-connection`, except this automatically
  wraps `body` in a database transaction with the specified transaction
  isolation level.  See isolation-levels for possible values."
  [db-spec tx-isolation-level & body]
  `(with-transacted-connection-fn ~db-spec ~tx-isolation-level
     (fn []
       ~@body)))

(defmacro with-transacted-connection
  "Like `clojure.java.jdbc/with-connection`, except this automatically
  wraps `body` in a database transaction."
  [db-spec & body]
  `(with-transacted-connection-fn ~db-spec nil
     (fn []
       ~@body)))

(defn with-query-results-cursor*
  "Executes the given parameterized query within a transaction,
  producing a lazy sequence of rows. The callback `func` is executed
  on the entire sequence.

  The lazy sequence is backed by an active database cursor, and is thus
  useful for streaming very large resultsets.

  The cursor is closed when `func` returns. If an exception is thrown,
  the query is cancelled."
  [func sql params]
  (sql/transaction
   (with-open [stmt (.prepareStatement (sql/connection) sql)]
     (doseq [[index value] (map vector (iterate inc 1) params)]
       (.setObject stmt index value))
     (.setFetchSize stmt 500)
     (with-open [rset (.executeQuery stmt)]
       (try
         (-> rset
             (sql/resultset-seq)
             (convert-result-arrays)
             (func))
         (catch Exception e
           ;; Cancel the current query
           (.cancel stmt)
           (throw e)))))))

(defmacro with-query-results-cursor
  "Executes the given parameterized query within a transaction.
  `body` is then executed with `rs-var` bound to the lazy sequence of
  resulting rows. See `with-query-results-cursor*`."
  [sql params rs-var & body]
  `(let [func# (fn [~rs-var] (do ~@body))]
     (with-query-results-cursor* func# ~sql ~params)))

(defn make-connection-pool
  "Create a new database connection pool"
  [{:keys [classname subprotocol subname user username password
           partition-conn-min partition-conn-max partition-count
           stats log-statements log-slow-statements statements-cache-size
           conn-max-age conn-lifetime conn-keep-alive read-only?]
    :as   db}]
  (let [;; Load the database driver class explicitly, to avoid jar load ordering
        ;; issues.
        _ (Class/forName classname)
        log-slow-statements-duration (pl-time/to-secs log-slow-statements)
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
                          (.setDefaultReadOnly read-only?))
        user (or user username)]
    ;; configurable without default
    (when user (.setUsername config (str user)))
    (when password (.setPassword config (str password)))
    (when conn-lifetime (.setMaxConnectionAge config (pl-time/to-minutes conn-lifetime) TimeUnit/MINUTES))
    (when log-statements (.setLogStatementsEnabled config log-statements))

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
