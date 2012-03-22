;; ## SQL query compiler

(ns com.puppetlabs.cmdb.query.resource
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [com.puppetlabs.utils :as utils]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use clojureql.core
        [com.puppetlabs.jdbc :only [query-to-vec convert-result-arrays]]
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize sql-array-query-string]]
        [clojure.core.match :only [match]]))

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
    ["(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM catalog_resources)"]
    (compile-query->sql db query)))

(defn query-resources
  "Take a vector-structured query, and return a vector of resources
and their parameters which match."
  [db [sql & params]]
  {:pre [(string? sql)
         (map? db)]}
  (let [query (str "SELECT certname_catalogs.certname, cr.resource, cr.type, cr.title,"
                   "cr.tags, cr.exported, cr.sourcefile, cr.sourceline, rp.name, rp.value "
                   "FROM catalog_resources cr "
                   "JOIN certname_catalogs USING(catalog) "
                   "LEFT OUTER JOIN resource_params rp "
                   "ON cr.resource = rp.resource "
                   "WHERE (cr.catalog,cr.resource) IN "
                   sql)
        results (sql/with-connection db
                   (apply query-to-vec query params))
        metadata_cols [:certname :resource :type :title :tags :exported :sourcefile :sourceline]
        metadata (apply juxt metadata_cols)]
    (vec (for [[resource params] (group-by metadata results)]
           (assoc (zipmap metadata_cols resource) :parameters
                  (into {} (for [param params :when (:name param)]
                             [(:name param) (json/parse-string (:value param))])))))))

;; Compile an '=' predicate, the basic atom of a resource query. This
;; will produce a query that selects a set of hashes matching the
;; predicate, which can then be combined with connectives to build
;; complex queries.
(defmethod compile-query->sql "="
  [db [op path value :as term]]
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (let [catalog_resources (-> (table :catalog_resources)
                            (project [:catalog_resources.catalog :catalog_resources.resource])
                            (distinct))
        tbl (match [path]
              ;; tag join.
              ["tag"]
                   [(format "SELECT DISTINCT catalog,resource FROM catalog_resources WHERE %s"
                            (sql-array-query-string "tags"))
                    value]
              ;; node join.
              [["node" "name"]]
                   (let [certname_catalogs (-> (table :certname_catalogs)
                                             (select (where
                                                       (= :certname_catalogs.certname value)))
                                             (project [])
                                             ;; ClojureQL loses the DISTINCT when we join unless it's on the left side as well
                                             (distinct))]
                     (join certname_catalogs catalog_resources :catalog))
              ;; {in,}active nodes.
              [["node" "active"]]
                   (let [certname_catalogs (-> (table :certname_catalogs)
                                             (join (table :certnames)
                                                   (where (= :certname_catalogs.certname
                                                             :certnames.name)))
                                             (select (where (if value
                                                              (= :certnames.deactivated nil)
                                                              (not (= :certnames.deactivated nil)))))
                                             (project [])
                                             (distinct))]
                     (join certname_catalogs catalog_resources :catalog))
              ;; param joins.
              [["parameter" (name :when string?)]]
                   (let [resource_params (-> (table :resource_params)
                                           (select (where
                                                     (and (= :resource_params.name name)
                                                          (= :resource_params.value (db-serialize value)))))
                                           (project [])
                                           (distinct))]
                     (join resource_params catalog_resources :resource))
              ;; metadata match.
              [(metadata :when string?)]
                   (select catalog_resources
                     (where (= (keyword metadata) value)))
              ;; ...else, failure
              :else (throw (IllegalArgumentException.
                           (str term " is not a valid query term"))))
        [sql & params] (if (table? tbl) (compile tbl db) tbl)]
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
                   (str "SELECT DISTINCT catalog,resource FROM ")
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
                    (format (str "SELECT DISTINCT lhs.catalog,lhs.resource FROM catalog_resources lhs "
                            "LEFT OUTER JOIN %s rhs "
                            "ON lhs.catalog = rhs.catalog AND lhs.resource = rhs.resource "
                            "WHERE (rhs.resource IS NULL)"))
                    (format "(%s)"))]
    (apply vector query params)))
