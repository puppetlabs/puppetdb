(ns com.puppetlabs.cmdb.query.node
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use clojureql.core
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize sql-array-query-string sql-as-numeric]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.utils :only [parse-number]]))

(defn search
  "Search for nodes satisfying the given filter."
  [db [sql & params :as filter-expr]]
  {:pre [(string? sql)
         (every? (complement coll?) params)]
   :post [(vector? %)
          (every? string? %)]}
  (let [nodes (sql/with-connection db
               (query-to-vec filter-expr))]
    (-> (map :certname nodes)
      (sort)
      (vec))))

(defn fetch-all
  "Retrieves all nodes from the database."
  [db]
  (search db ["SELECT name AS certname FROM certnames"]))

(defmulti compile-predicate->sql
  "Recursively compile a query into a collection of SQL operations."
  (fn [db query]
    (let [operator (string/lower-case (first query))]
      (cond
        (#{">" "<" ">=" "<="} operator) "numeric-comparison"
        :else operator))))

(defn query->sql
  [db query]
  {:pre [(sequential? query)]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) %)]}
  (compile-predicate->sql db query))

(defmethod compile-predicate->sql "="
  [db [op path value :as term]]
  {:pre [(string? value)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
               (format "operators take two arguments, but we found %d" (dec count))))))
  (let [tbl (match [path]
              [["fact" (name :when string?)]]
              (-> (table :certname_facts)
                (select (where
                          (and (= :certname_facts.fact name)
                               (= :certname_facts.value value))))
                (project [:certname_facts.certname])
                (distinct))
              :else (throw (IllegalArgumentException.
                             (str term " is not a valid query term"))))
        [sql & params] (compile tbl db)]
    (apply vector (format "(%s)" sql) params)))

(defmethod compile-predicate->sql "numeric-comparison"
  [db [op path value :as term]]
  {:pre [(string? value)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
               (format "operators take two arguments, but we found %d" (dec count))))))
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
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map (partial compile-predicate->sql db) terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                (alias-subqueries)
                (string/join " NATURAL JOIN ")
                (str "SELECT DISTINCT certname FROM ")
                (format "(%s)"))]
    (apply vector query params)))

(defmethod compile-predicate->sql "or"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map (partial compile-predicate->sql db) terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                (string/join " UNION ")
                (format "(%s)"))]
    (apply vector query params)))

;; NOTE: This will potentially return nodes which don't have values for the
;; facts being tested.
(defmethod compile-predicate->sql "not"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [[subquery & params] (compile-predicate->sql db (cons "or" terms))
        query (->> subquery
                (format (str "SELECT DISTINCT lhs.name AS certname FROM certnames lhs "
                             "LEFT OUTER JOIN %s rhs "
                             "ON lhs.name = rhs.certname "
                             "WHERE (rhs.certname IS NULL)"))
                (format "(%s)"))]
    (apply vector query params)))
