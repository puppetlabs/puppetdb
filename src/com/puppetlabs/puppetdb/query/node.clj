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

(defmulti compile-term
  "Recursively compile a query into a structured map reflecting the terms of
  the query."
  (fn [query]
    (let [operator (string/lower-case (first query))]
      (cond
       (#{">" "<" ">=" "<="} operator) :numeric-comparison
       (#{"and" "or"} operator) :connective
       :else operator))))

(defn build-join-expr
  "Builds an inner join expression between catalog_resources and the given
  `table`. There aren't any actual possibilities for this currently, but the
  function is left here to aid in possible unification of various query paths."
  [table]
  (condp = table))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [query]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) (rest %))]}
  (if query
    (let [{:keys [where joins params]} (compile-term query)
          join-expr                    (->> joins
                                            (map build-join-expr)
                                            (string/join " "))]
      (apply vector (format "%s WHERE %s" join-expr where) params))
    [""]))

(defn search
  "Search for nodes satisfying the given SQL filter."
  [[sql & params :as filter-expr]]
  {:pre  [(string? sql)
          (every? (complement coll?) params)]
   :post [(vector? %)
          (every? string? %)]}
  (let [query (format "SELECT name AS certname FROM certnames %s" sql)
        nodes (apply query-to-vec query params)]
    (-> (map :certname nodes)
        (sort)
        (vec))))

(defn fetch-all
  "Retrieves all nodes from the database."
  []
  (search [""]))

(defmethod compile-term "="
  [[op path value :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (match [path]
         [["fact" (name :when string?)]]
         {:where  "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.fact = ? AND cf.value = ?)"
          :params [name (str value)]}
         [["node" "active"]]
         {:where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

         :else (throw (IllegalArgumentException.
                       (str term " is not a valid query term")))))

(defmethod compile-term :numeric-comparison
  [[op path value :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (if-let [number (parse-number (str value))]
    (match [path]
           [["fact" (name :when string?)]]
           {:where  (format "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.fact = ? AND %s %s ?)" (sql-as-numeric "cf.value") op)
            :params [name number]}

           :else (throw (IllegalArgumentException.
                         (str term " is not a valid query term"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

;; Join a set of predicates together with an 'and' relationship,
;; performing an intersection (via natural join).
(defmethod compile-term :connective
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms  (map compile-term terms)
        joins  (distinct (mapcat :joins terms))
        params (mapcat :params terms)
        query  (->> (map :where terms)
                    (map #(format "(%s)" %))
                    (string/join (format " %s " (string/upper-case op))))]
    {:joins  joins
     :where  query
     :params params}))

;; Join a set of predicates together with a 'not' relationship,
;; performing a set difference. This will reject resources matching
;; _any_ child predicate.
(defmethod compile-term "not"
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [term  (compile-term (cons "or" terms))
        query (format "NOT (%s)" (:where term))]
    (assoc term :where query)))
