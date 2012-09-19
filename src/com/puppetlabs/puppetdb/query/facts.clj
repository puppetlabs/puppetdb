;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [com.puppetlabs.jdbc :as sql])
  (:use [com.puppetlabs.utils :only [parse-number]]
        [com.puppetlabs.puppetdb.scf.storage :only [sql-as-numeric]]
        [clojure.core.match :only [match]]
        clojureql.core))

(defn facts-for-node
  "Fetch the facts for the given node, as a map of `{fact value}`"
  [node]
  {:pre  [(string? node)]
   :post [(map? %)]}
  (let [facts (-> (table :certname_facts)
                  (project [:fact, :value])
                  (select (where (= :certname node))))]
    (into {} (for [fact @facts]
               [(:fact fact) (:value fact)]))))

(defn flat-facts-by-node
  "Similar to `facts-for-node`, but returns facts in the form:

    [{:node <node> :fact <fact> :value <value>}
     ...
     {:node <node> :fact <fact> :value <value>}]"
  [node]
  (-> (table :certname_facts)
    (project [[:certname :as :node] :fact :value])
    (select (where (= :certname node)))
    (deref)))

(defmulti compile-term
  "Recursively compile a query into a structured map reflecting the terms of
  the query."
  (fn [[op & args :as term]]
    (when-not op
      (throw (IllegalArgumentException.
               (format "%s is not well-formed; queries must contain at least one operator" term))))
    (let [operator (string/lower-case op)]
      (cond
        (#{">" "<" ">=" "<="} operator) :numeric-comparison
        (#{"and" "or"} operator) :connective
        :else operator))))

(defmethod compile-term "="
  [[op path value :as term]]
  (let [count (count term)]
    (when (not= 3 count)
      (throw (IllegalArgumentException.
               (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (match [path]
         [["fact" "name"]]
         {:where "certname_facts.fact = ?"
          :params [value]}

         [["fact" "value"]]
         {:where "certname_facts.value = ?"
          :params [(str value)]}

         [["node" "name"]]
         {:where "certname_facts.certname = ?"
          :params [value]}))

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
           [["fact" "value"]]
           ;; This is like convert_to_numeric(certname_facts.value) > 0.3
           {:where  (format "%s %s ?" (sql-as-numeric "certname_facts.value") op)
            :params [number]}

           :else (throw (IllegalArgumentException.
                         (str term " is not a valid query term"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(defmethod compile-term "not"
  [[op & terms]]
  {:pre  [(every? vector? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [term  (compile-term (cons "or" terms))
        query (format "NOT (%s)" (:where term))]
    (assoc term :where query)))

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

(defn build-join-expr
  "Builds an inner join expression between certname_facts and the given
  `table`. There aren't any actual possibilities for this currently, but the
  function is left here to aid in possible unification of various query paths."
  [table]
  (condp = table))

(defn query->sql
  "Compile a query into an SQL expression."
  [query]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) (rest %))]}
  (if query
    (let [{:keys [where joins params]} (compile-term query)
          join-expr                   (->> joins
                                        (map build-join-expr)
                                        (string/join " "))]
      (apply vector (format "%s WHERE %s" join-expr where) params))
    [""]))

(defn query-facts
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [query (format "SELECT certname AS node, fact, value FROM certname_facts %s ORDER BY node, certname_facts.fact, certname_facts.value" sql)]
    (apply sql/query-to-vec query params)))
