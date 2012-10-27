;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [clojure.string :as string]
            [com.puppetlabs.jdbc :as sql]
            [com.puppetlabs.puppetdb.query.resource :as resource])
  (:use [com.puppetlabs.utils :only [parse-number]]
        [com.puppetlabs.puppetdb.scf.storage :only [sql-as-numeric sql-regexp-match]]
        [clojure.core.match :only [match]]
        clojureql.core))

(defn facts-for-node
  "Fetch the facts for the given node, as a map of `{fact value}`. This is used
  for the deprecated v1 facts API."
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

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated nodes."
  []
  {:post [(coll? %)
          (every? string? %)]}
  (let [facts (-> (table :certname_facts)
                  (project [:fact])
                  (distinct)
                  (order-by [:fact]))]
    (map :fact @facts)))

(defn build-join-expr
  "Builds an inner join expression between certname_facts and the given
  `table`. The only acceptable value for now is `certnames`, which will just
  join directly between `certname_facts` and `certnames`."
  [table]
  (condp = table
    :certnames
    "INNER JOIN certnames ON certname_facts.certname = certnames.name"))

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
        (#{"~"} operator) :regexp-comparison
        (#{"and" "or"} operator) :connective
        :else operator))))

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
                                           (string/join " "))
          sql (format "SELECT certname AS node, fact, value FROM certname_facts %s WHERE %s ORDER BY node, certname_facts.fact, certname_facts.value" join-expr where)]
      (apply vector sql params))
    ["SELECT certname AS node, fact, value FROM certname_facts ORDER BY node, certname_facts.fact, certname_facts.value"]))

(defn query-facts
  [[sql & params]]
  {:pre [(string? sql)]}
  (apply sql/query-to-vec sql params))

(defmethod compile-term "="
  [[op path value :as term]]
  {:post [(map? %)
          (:where %)]}
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
          :params [value]}

         [["node" "active"]]
         {:joins [:certnames]
          :where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}))

(defmethod compile-term "select-facts"
  [[_ subquery & others]]
  {:pre [(coll? subquery)]
   :post [(string? (:where %))]}
  (when-not (empty? others)
    (throw (IllegalArgumentException. "Only one expression is accepted for 'select-facts'")))
  (let [[subsql & params] (query->sql subquery)]
    {:where (format "SELECT * FROM (%s) r1" subsql)
     :params params}))

(defmethod compile-term "select-resources"
  [[_ subquery & others]]
  {:pre [(coll? subquery)]
   :post [(string? (:where %))]}
  (when-not (empty? others)
    (throw (IllegalArgumentException. "Only one expression is accepted for 'select-resources'")))
  (let [[subsql & params] (resource/query->sql subquery)]
    {:where (format "SELECT * FROM (%s) r1" subsql)
     :params params}))

(def fact-columns #{"certname" "node" "fact" "value"})

(def resource-columns #{"certname" "catalog" "resource" "type" "title" "tags" "exported" "sourcefile" "sourceline"})

(def selectable-columns
  {"select-resources" resource-columns
   "select-facts" fact-columns})

(defmethod compile-term "project"
  [[_ field subselect]]
  {:pre [(string? field)
         (coll? subselect)]
   :post [(map? %)
          (string? (:where %))]}
  (let [{:keys [where params] :as query} (compile-term subselect)
        select-type (first subselect)
        field-names (selectable-columns select-type)]
    (when-not (field-names field)
      (throw (IllegalArgumentException. (format "Can't project unknown field '%s' for '%s'" field select-type))))
    (assoc query :where (format "SELECT r1.%s FROM (%s) r1" field where))))

(defmethod compile-term "in-result"
  [[_ [type field] subselect]]
  {:pre [(string? type)
         (string? field)
         (coll? subselect)]
   :post [(map? %)
          (string? (:where %))]}
  (let [{:keys [where params] :as query} (compile-term subselect)]
    (when-not (fact-columns field)
      (throw (IllegalArgumentException. (format "Can't match on unknown %s field '%s' for 'in-result'" type field))))
    (assoc query :where (format "%s IN (%s)" field where))))

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

(defmethod compile-term :regexp-comparison
  [[op path pattern :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
              (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (let [query (fn [col] {:where (sql-regexp-match col) :params [pattern]})]
    (match [path]
           [["node" "name"]]
           (query "certname_facts.certname")

           [["fact" "name"]]
           (query "certname_facts.fact")

           [["fact" "value"]]
           (query "certname_facts.value")

           :else (throw (IllegalArgumentException.
                         (str path " is not a valid operand for regexp comparison"))))))

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
