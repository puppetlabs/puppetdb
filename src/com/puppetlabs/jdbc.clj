(ns com.puppetlabs.jdbc
  (:require [clojure.java.jdbc :as sql]
            [com.puppetlabs.utils :as utils]))

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
       (let [arrays-to-vecs #(cond
                              (utils/array? %) (vec %)
                              (isa? (class %) java.sql.Array) (vec (.getArray %))
                              :else %)]
         (->> result-set
              (map #(utils/mapvals arrays-to-vecs %))
              (vec))))))
