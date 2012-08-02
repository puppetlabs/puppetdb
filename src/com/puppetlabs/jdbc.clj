;; ## Database utilities

(ns com.puppetlabs.jdbc
  (:import (com.jolbox.bonecp BoneCPDataSource BoneCPConfig)
           (java.util.concurrent TimeUnit))
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [com.puppetlabs.utils :as utils])
  (:use com.puppetlabs.jdbc.internal))

(defn convert-result-arrays
  "Converts Java and JDBC arrays in a result set using the provided function
  (eg. vec, set). Values which aren't arrays are unchanged."
  ([result-set]
     (convert-result-arrays vec result-set))
  ([f result-set]
     (let [convert #(cond
                     (utils/array? %) (f %)
                     (isa? (class %) java.sql.Array) (f (.getArray %))
                     :else %)]
       (map #(utils/mapvals convert %) result-set))))

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
  {:pre [(and (integer? limit) (>= limit 0))
         ((some-fn string? vector?) query)]}
  (let [query-vector (if (string? query) [query] query)
        sql-query-and-params (add-limit-clause* limit query-vector)]
    (sql/with-query-results result-set
      sql-query-and-params
      (let [limited-result-set (limit-result-set!* limit result-set)]
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

(defn table-count
  "Returns the number of rows in the supplied table"
  [table]
  (-> "SELECT COUNT(*) as c FROM %s"
      (format table)
      (query-to-vec)
      (first)
      :c))

(defmacro with-transacted-connection
  "Like `clojure.java.jdbc/with-connection`, except this automatically
  wraps `body` in a database transaction."
  [db-spec & body]
  `(sql/with-connection ~db-spec
     (sql/transaction
      ~@body)))

(defn make-connection-pool
  "Create a new database connection pool"
  [{:keys [classname subprotocol subname username password
           partition-conn-min partition-conn-max partition-count
           stats log-statements log-slow-statements]
    :or   {partition-conn-min 1
           partition-conn-max 10
           partition-count    5
           stats              true
          ;; setting this to a String value, because that's what it would
          ;;  be in the config file and we're manually converting it to a boolean
           log-statements     "true"
           log-slow-statements 10}
    :as   db}]
  ;; Load the database driver class
  (Class/forName classname)
  (let [log-statements? (Boolean/parseBoolean log-statements)
        config (doto (new BoneCPConfig)
                (.setDefaultAutoCommit false)
                (.setLazyInit true)
                (.setMinConnectionsPerPartition partition-conn-min)
                (.setMaxConnectionsPerPartition partition-conn-max)
                (.setPartitionCount partition-count)
                (.setStatisticsEnabled stats)
                ;; paste the URL back together from parts.
                (.setJdbcUrl (str "jdbc:" subprotocol ":" subname))
                (.setConnectionHook (connection-hook* log-statements? log-slow-statements)))]
    ;; configurable without default
    (when username (.setUsername config username))
    (when password (.setPassword config password))
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
