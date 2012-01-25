(ns com.puppetlabs.cmdb.query.resource
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [com.puppetlabs.utils :as utils]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use clojureql.core
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize]]
        [clojure.core.match.core :only [match]]))


;; ## SQL query compiler

(defmulti compile-query->sql
  "Recursively compile a query into a collection of SQL operations."
  (fn [db query]
    (string/lower-case (first query))))

(defn query->sql
  "Compile a vector-structured query into an SQL expression.
An empty query gathers all resources."
  [db query]
  {:pre  [(or (nil? query) (vector? query))]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) (rest %))]}
  (if (nil? query)
    (-> (table :resources)
        (project [:hash])
        (distinct)
        (compile db))
    (compile-query->sql db query)))

(defn query-resources
  "Take a vector-structured query, and return a vector of resources
and their parameters which match."
  [db query]
  {:pre [(map? db)]}
  (let [hashes (sql/with-connection db
                   (->> (query-to-vec query)
                        (map :hash)))]
    ;; We have to special-case this or we get invalid queries generated later
    (if (empty? hashes)
      []
      (let [resources (future
                        (-> (table db :resources)
                            (select (where
                                      (in :hash hashes)))
                            (deref)))
            params (future
                     (-> (table db :resource_params)
                         (select (where
                                 (in :resource hashes)))
                         (deref)))
            params (->> @params
                     (group-by :resource)
                     (utils/mapvals
                       (partial reduce
                                #(assoc %1 (:name %2) (json/parse-string (:value %2))) {})))]

    (vec (map #(if-let [params (get params (:hash %1))]
                 (assoc %1 :parameters params)
                 %1)
              @resources))))))

;; Compile an '=' predicate, the basic atom of a resource query. This
;; will produce a query that selects a set of hashes matching the
;; predicate, which can then be combined with connectives to build
;; complex queries.
(defmethod compile-query->sql "="
  [db [op path value :as term]]
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
              (format "operators take two arguments, but we found %d" (dec count))))))
  (let [tbl (-> (table :resources)
                (distinct))
        tbl (match [path]
              ;; tag join.
              ["tag"]
                   (-> tbl
                     (join (table :resource_tags)
                           (where
                             (= :resources.hash :resource_tags.resource)))
                     (select
                       (where
                         (= :resource_tags.name value)))
                     (project [:resources.hash]))
              ;; node join.
              [["node" (field :when string?)]]
                   (-> tbl
                     (join (table :catalog_resources)
                           (where
                             (= :resources.hash :catalog_resources.resource)))
                     (join (table :certname_catalogs)
                           (where
                             (= :catalog_resources.catalog :certname_catalogs.catalog)))
                     (select
                       (where
                         (= :certname_catalogs.certname value)))
                     (project [:resources.hash]))
              ;; param joins.
              [["parameter" (name :when string?)]]
                   (-> tbl
                     (join (table :resource_params)
                           (where
                             (= :resource_params.resource :resources.hash)))
                     (select
                       (where (and
                                (= :resource_params.name name)
                                (= :resource_params.value (db-serialize value)))))
                     (project [:resources.hash]))
              ;; simple string match.
              [(column :when string?)]
                   (-> tbl
                     (select (where
                               (= (keyword column) value)))
                     (project [:resources.hash]))
              ;; ...else, failure
              :else (throw (IllegalArgumentException.
                           (str term " is not a valid query term"))))
        [sql & params] (compile tbl db)]
      (apply vector (format "(%s)" sql) params)))

(defn- alias-subqueries
  "Produce distinct aliases for a list of queries, suitable for a join
operation."
  [queries]
  (let [ids (range (count queries))]
    (map #(format "%s resources_%d" %1 %2) queries ids)))

;; Join a set of predicates together with an 'and' relationship,
;; performing an intersection (via natural join).
(defmethod compile-query->sql "and"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map (partial compile-query->sql db) terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                   (alias-subqueries)
                   (string/join " NATURAL JOIN ")
                   (str "SELECT DISTINCT hash FROM ")
                   (format "(%s)"))]
    (apply vector query params)))

;; Join a set of predicates together with an 'or' relationship,
;; performing a union operation.
(defmethod compile-query->sql "or"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map (partial compile-query->sql db) terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                   (string/join " UNION ")
                   (format "(%s)"))]
    (apply vector query params)))

;; Join a set of predicates together with a 'not' relationship,
;; performing a set difference. This will reject resources matching
;; _any_ child predicate.
(defmethod compile-query->sql "not"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [[subquery & params] (compile-query->sql db (cons "or" terms))
         query (->> subquery
                    (format "SELECT DISTINCT lhs.hash FROM resources lhs LEFT OUTER JOIN %s rhs ON (lhs.hash = rhs.hash) WHERE (rhs.hash IS NULL)")
                    (format "(%s)"))]
    (apply vector query params)))
