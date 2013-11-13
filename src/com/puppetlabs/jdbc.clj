;; ## Database utilities

(ns com.puppetlabs.jdbc
  (:import (com.jolbox.bonecp BoneCPDataSource BoneCPConfig)
           (java.util.concurrent TimeUnit))
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.jdbc.internal :as jint]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str])
  (:use com.puppetlabs.jdbc.internal))


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

(defn add-limit-clause
  "Helper function for ensuring that a query does not return more than a certain
  number of results.  (Adds a limit clause to an SQL query if necessary.)

  Accepts two parameters: `limit` and `query`.  `query` should be an SQL query
  (String) that you wish to apply a LIMIT clause to.

  `limit` is an integer specifying the maximum number of results that we are looking
  for.  If `limit` is zero, then we return the original `query` unaltered.  If
  `limit is greater than zero, we add a limit clause using the time-honored trick
  of using the value of `limit + 1`;  This allows us to later compare the size of
  the result set against the original limit and detect cases where we've exceeded
  the maximum."
  [limit query]
  {:pre [(and (integer? limit) (>= limit 0))
         (string? query)]}
  (if (pos? limit)
    (format "select results.* from (%s) results LIMIT %s" query (inc limit))
    query))

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
  "Given a list of legal result columns and a map containing a single order-by term,
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
  an order-by term), return the SQL string representing the ORDER BY clause
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

(defn paged-sql
  "Given a sql string and a map of paging options, return a modified SQL string
  that contains the necessary LIMIT/OFFSET/ORDER BY clauses.  The map of paging
  options can contain any of the following keys:

  * :limit  (int)
  * :offset (int)
  * :order-by (array of maps; each map is an order-by term, consisting of
      required key :field and optional key :order.  Legal values for :order
      include 'asc' or 'desc'.)

  Note that if no paging options are specified, the original SQL will be
  returned completely unmodified."
  [sql {:keys [limit offset order-by]}]
  {:pre [(string? sql)
         ((some-fn nil? integer?) limit)
         ((some-fn nil? integer?) offset)
         ((some-fn nil? sequential?) order-by)
         (every? kitchensink/order-by-expr? order-by)]
   :post [(string? %)]}
    (let [limit-clause     (if limit (format " LIMIT %s" limit) "")
          offset-clause    (if offset (format " OFFSET %s" offset) "")
          order-by-clause  (order-by->sql order-by)]
      (format "SELECT paged_results.* FROM (%s) paged_results%s%s%s"
          sql
          order-by-clause
          limit-clause
          offset-clause)))

(defn count-sql
  "Takes a sql string and returns a modified sql string that will select
  the count of results that would be returned by the original sql."
  [sql]
  {:pre   [(string? sql)]
   :post  [(string? %)]}
  (format "SELECT COUNT(*) AS result_count FROM (%s) results_to_count" sql))

(defn get-result-count
  "Takes a sql string, executes a `COUNT` statement against the database,
  and returns the number of results that the original query would have returned."
  [[count-sql & params]]
  {:pre [(string? count-sql)]
   :post [(integer? %)]}
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

(defn with-transacted-connection-fn
  "Function for creating a connection that has the specified isolation
   level.  If one is not specified, the JDBC default will be used (read-committed)"
  [db-spec tx-isolation-level f]
  {:pre [(or (nil? tx-isolation-level)
             (get isolation-levels tx-isolation-level))]}
  (sql/with-connection db-spec
    (when-let [isolation-level (get isolation-levels tx-isolation-level)]
      (.setTransactionIsolation (:connection jint/*db*) isolation-level))
     (sql/transaction
      (f))))

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
           stats log-statements log-slow-statements
           conn-max-age conn-lifetime conn-keep-alive read-only?]
    :or   {partition-conn-min  1
           partition-conn-max  25
           partition-count     1
           stats               true
           ;; setting this to a String value, because that's what it would
           ;;  be in the config file and we're manually converting it to a boolean
           log-statements      "true"
           log-slow-statements 10
           conn-max-age        60
           conn-keep-alive     45
           read-only?          false}
    :as   db}]
  ;; Load the database driver class
  (Class/forName classname)
  (let [log-statements? (Boolean/parseBoolean log-statements)
        config          (doto (new BoneCPConfig)
                          (.setDefaultAutoCommit false)
                          (.setLazyInit true)
                          (.setMinConnectionsPerPartition partition-conn-min)
                          (.setMaxConnectionsPerPartition partition-conn-max)
                          (.setPartitionCount partition-count)
                          (.setStatisticsEnabled stats)
                          (.setIdleMaxAgeInMinutes conn-max-age)
                          (.setIdleConnectionTestPeriodInMinutes conn-keep-alive)
                          ;; paste the URL back together from parts.
                          (.setJdbcUrl (str "jdbc:" subprotocol ":" subname))
                          (.setConnectionHook (connection-hook log-statements? log-slow-statements))
                          (.setDefaultReadOnly read-only?))
        user (or user username)]
    ;; configurable without default
    (when user (.setUsername config (str user)))
    (when password (.setPassword config (str password)))
    (when conn-lifetime (.setMaxConnectionAge config conn-lifetime TimeUnit/MINUTES))
    (when log-statements? (.setLogStatementsEnabled config log-statements?))
    (when log-slow-statements
      (.setQueryExecuteTimeLimit config log-slow-statements (TimeUnit/SECONDS)))
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
