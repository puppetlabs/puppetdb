;; ## Node query
;;
;; This implements the node query operations according to the [node query
;; spec](../spec/node.md).
;;
(ns com.puppetlabs.puppetdb.query.node
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string])
  (:use clojureql.core
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-array-query-string sql-as-numeric]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection]]
        [com.puppetlabs.utils :only [parse-number]]))

(defmulti compile-predicate->sql
  "Recursively compile a query into a collection of SQL operations."
  (fn [query]
    (let [operator (string/lower-case (first query))]
      (cond
       (#{">" "<" ">=" "<="} operator) :numeric-comparison
       :else operator))))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [query]
  {:pre  [(some-fn nil? sequential? query)]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) %)]}
  (if query
    (compile-predicate->sql query)
    ["SELECT name AS certname FROM certnames"]))

(defn search
  "Search for nodes satisfying the given SQL filter."
  [[sql & params :as filter-expr]]
  {:pre  [(string? sql)
          (every? (complement coll?) params)]
   :post [(vector? %)
          (every? string? %)]}
  (let [nodes (query-to-vec filter-expr)]
    (-> (map :certname nodes)
        (sort)
        (vec))))

(defn fetch-all
  "Retrieves all nodes from the database."
  []
  (search (query->sql nil)))

(defmethod compile-predicate->sql "="
  [[op path value :as term]]
  {:post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (let [tbl            (match [path]
                              [["fact" (name :when string?)]]
                              (-> (table :certname_facts)
                                  (select (where
                                           (and (= :certname_facts.fact name)
                                                (= :certname_facts.value value))))
                                  (project [:certname_facts.certname])
                                  (distinct))
                              [["node" "active"]]
                              (-> (table :certnames)
                                  (select (where (if value
                                                   (= :certnames.deactivated nil)
                                                   (not (= :certnames.deactivated nil)))))
                                  (project [[:certnames.name :as :certname]])
                                  (distinct))
                              :else (throw (IllegalArgumentException.
                                            (str term " is not a valid query term"))))
        [sql & params] (compile tbl nil)]
    (apply vector (format "(%s)" sql) params)))

(defmethod compile-predicate->sql :numeric-comparison
  [[op path value :as term]]
  {:pre  [(string? value)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (let [[sql & params] (match [path]
                              [["fact" (name :when string?)]]
                              [(format (str "SELECT DISTINCT certname FROM certname_facts "
                                            "WHERE certname_facts.fact = ? AND %s %s ?")
                                       (sql-as-numeric "certname_facts.value") op)
                               name (parse-number value)]
                              :else (throw (IllegalArgumentException.
                                            (str term " is not a valid query term"))))]
    (apply vector (format "(%s)" sql) params)))

(defn- alias-subqueries
  "Produce distinct aliases for a list of queries, suitable for a join
operation."
  [queries]
  (let [ids (range (count queries))]
    (map #(format "%s resources_%d" %1 %2) queries ids)))

(defmethod compile-predicate->sql "and"
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms  (map compile-predicate->sql terms)
        params (mapcat rest terms)
        query  (->> (map first terms)
                    (alias-subqueries)
                    (string/join " NATURAL JOIN ")
                    (str "SELECT DISTINCT certname FROM ")
                    (format "(%s)"))]
    (apply vector query params)))

(defmethod compile-predicate->sql "or"
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms  (map compile-predicate->sql terms)
        params (mapcat rest terms)
        query  (->> (map first terms)
                    (string/join " UNION ")
                    (format "(%s)"))]
    (apply vector query params)))

;; NOTE: This will include nodes which don't have values for the facts
;; referenced in the query.
(defmethod compile-predicate->sql "not"
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [[subquery & params] (compile-predicate->sql (cons "or" terms))
        query               (->> subquery
                                 (format (str "SELECT DISTINCT lhs.name AS certname FROM certnames lhs "
                                              "LEFT OUTER JOIN %s rhs "
                                              "ON lhs.name = rhs.certname "
                                              "WHERE (rhs.certname IS NULL)"))
                                 (format "(%s)"))]
    (apply vector query params)))
