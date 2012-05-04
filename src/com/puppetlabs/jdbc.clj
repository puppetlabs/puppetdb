;; ## Database utilities

(ns com.puppetlabs.jdbc
  (:import (com.jolbox.bonecp BoneCPDataSource BoneCPConfig)
           (java.util.concurrent TimeUnit))
  (:require [clojure.java.jdbc :as sql]
            [com.puppetlabs.utils :as utils]))

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
     (sql/with-query-results result-set
       (if (string? sql-query-and-params) [sql-query-and-params] sql-query-and-params)
       (-> result-set
           (convert-result-arrays)
           (vec)))))

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
           stats              true}
    :as   db}]
  ;; Load the database driver class
  (Class/forName classname)
  (let [config (doto (new BoneCPConfig)
                 (.setDefaultAutoCommit false)
                 (.setLazyInit true)
                 (.setMinConnectionsPerPartition partition-conn-min)
                 (.setMaxConnectionsPerPartition partition-conn-max)
                 (.setPartitionCount partition-count)
                 (.setStatisticsEnabled stats)
                 ;; paste the URL back together from parts.
                 (.setJdbcUrl (str "jdbc:" subprotocol ":" subname)))]
    ;; configurable without default
    (when username (.setUsername config username))
    (when password (.setPassword config password))
    (when log-statements (.setLogStatementsEnabled config log-statements))
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
