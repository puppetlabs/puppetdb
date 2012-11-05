;; ## Query
;;
;; This implements generic query operators used for each query endpoint.
;;
(ns com.puppetlabs.puppetdb.query
  (:require [clojure.string :as string])
  (:use [com.puppetlabs.utils :only [parse-number]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-as-numeric sql-array-query-string sql-regexp-match sql-regexp-array-match]]
        [clojure.core.match :only [match]]))

(declare compile-term)

(defn compile-boolean-operator*
  [op ops & terms]
  {:pre  [(every? coll? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [compiled-terms (map #(compile-term ops %) terms)
        joins  (distinct (mapcat :joins compiled-terms))
        params (mapcat :params compiled-terms)
        query  (->> (map :where compiled-terms)
                    (map #(format "(%s)" %))
                    (string/join (format " %s " (string/upper-case op))))]
    {:joins  joins
     :where  query
     :params params}))

(def compile-and
  (partial compile-boolean-operator* "and"))

(def compile-or
  (partial compile-boolean-operator* "or"))

(defn compile-not
  [ops & terms]
  {:pre  [(every? vector? terms)
          (every? coll? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str "not requires at least one term"))))
  (let [term  (compile-term ops (cons "or" terms))
        query (format "NOT (%s)" (:where term))]
    (assoc term :where query)))

(def fact-columns #{"certname" "fact" "value"})

(def resource-columns #{"certname" "catalog" "resource" "type" "title" "tags" "exported" "sourcefile" "sourceline"})

(def selectable-columns
  {:resource resource-columns
   :fact fact-columns})

(def subquery->type
  {"select-resources" :resource
   "select-facts" :fact})

(defn compile-project
  [ops field subquery]
  {:pre [(string? field)
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (let [[subselect & params] (compile-term ops subquery)
        subquery-type (subquery->type (first subquery))]
    (when-not subquery-type
      (throw (IllegalArgumentException. (format "The argument to project must be a select operator, not %s" (first subquery)))))
    (when-not (get-in selectable-columns [subquery-type field])
      (throw (IllegalArgumentException. (format "Can't project unknown %s field '%s'" (name subquery-type) field))))
    {:where (format "SELECT r1.%s FROM (%s) r1" field subselect)
     :params params}))

(defn compile-in-result
  [kind ops field subquery]
  {:pre [(string? field)
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (when-not (get-in selectable-columns [kind field])
    (throw (IllegalArgumentException. (format "Can't match on unknown %s field '%s' for 'in-result'" (name kind) field))))
  (let [{:keys [where] :as compiled-subquery} (compile-term ops subquery)]
    (assoc compiled-subquery :where (format "%s IN (%s)" field where))))

(defn join-tables
  "Constructs the appropriate join statement to `table` for a query of the
  specified `kind`."
  [kind table]
  (condp = [kind table]
        [:fact :certnames]
        "INNER JOIN certnames ON certname_facts.certname = certnames.name"

        [:resource :certnames]
        "INNER JOIN certnames ON certname_catalogs.certname = certnames.name"))

(defn build-join-expr
  "Constructs the entire join statement from `lhs` to each table specified in
  `joins`."
  [lhs joins]
  (->> joins
       (map #(join-tables lhs %))
       (string/join " ")))

(defn resource-query->sql
  [ops query]
  {:post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (let [{:keys [where joins params]} (compile-term ops query)
        join-stmt (build-join-expr :resource joins)
        sql (format "SELECT * FROM catalog_resources JOIN certname_catalogs USING(catalog) %s WHERE %s" join-stmt where)]
    (apply vector sql params)))

(defn fact-query->sql
  [ops query]
  {:post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (let [{:keys [where joins params]} (compile-term ops query)
        join-stmt (build-join-expr :fact joins)
        sql (format "SELECT * FROM certname_facts %s WHERE %s" join-stmt where)]
    (apply vector sql params)))

(defn compile-resource-equality*
  [path value]
  (match [path]
         ;; tag join. Tags are case-insensitive but always lowercase, so
         ;; lowercase the query value.
         ["tag"]
         {:where  (sql-array-query-string "tags")
          :params [(string/lower-case value)]}

         ;; node join.
         [["node" "name"]]
         {:where  "certname_catalogs.certname = ?"
          :params [value]}

         ;; {in,}active nodes.
         [["node" "active"]]
         {:joins [:certnames]
          :where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

         ;; param joins.
         [["parameter" (name :when string?)]]
         {:where  "catalog_resources.resource IN (SELECT rp.resource FROM resource_params rp WHERE rp.name = ? AND rp.value = ?)"
          :params [name (db-serialize value)]}

         ;; metadata match.
         [(metadata :when #{"catalog" :resource "type" "title" "tags" "exported" "sourcefile" "sourceline"})]
           {:where  (format "catalog_resources.%s = ?" metadata)
            :params [value]}

         ;; ...else, failure
         :else (throw (IllegalArgumentException.
                       (str path " is not a queryable object")))))

(defn compile-resource-equality
  [& [path value :as args]]
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (compile-resource-equality* path value))

(defn compile-resource-regexp
  "Compile an '~' predicate, which does regexp matching. This is done by
  leveraging the correct database-specific regexp syntax to return only rows
  where the supplied `path` match the given `pattern`."
  [path pattern]
  (match [path]
         ["tag"]
         {:where (sql-regexp-array-match "catalog_resources" "tags")
          :params [pattern]}

         ;; node join.
         [["node" "name"]]
         {:where  (sql-regexp-match "certname_catalogs.certname")
          :params [pattern]}

         ;; metadata match.
         [(metadata :when #{"catalog" "resource" "type" "title" "exported" "sourcefile" "sourceline"})]
         {:where  (sql-regexp-match (format "catalog_resources.%s" metadata))
          :params [pattern]}

         ;; ...else, failure
         :else (throw (IllegalArgumentException.
                        (str path " cannot be the target of a regexp match")))))

(defn compile-fact-equality
  [path value]
  {:pre [(sequential? path)]
   :post [(map? %)
          (:where %)]}
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
          :where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

         :else
         (throw (IllegalArgumentException. (str path " is not a queryable object for facts")))))

(defn compile-fact-regexp
  [path pattern]
  {:post [(map? %)
          (string? (:where %))]}
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
(defn compile-fact-inequality
  [op path value]
  {:pre [(sequential? path)]
   :post [(map? %)
          (string? (:where %))]}
  (if-let [number (parse-number (str value))]
    (match [path]
           [["fact" "value"]]
           ;; This is like convert_to_numeric(certname_facts.value) > 0.3
           {:where  (format "%s %s ?" (sql-as-numeric "certname_facts.value") op)
            :params [number]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for facts"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(declare fact-operators-v2)

(defn resource-operators-v1
  "Maps v1 resource query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [unsupported (fn [& args]
                      (throw (IllegalArgumentException. (format "Operator %s is not available in v1 resource queries" op))))]
    (condp = (string/lower-case op)
      "=" compile-resource-equality
      "and" (partial compile-and resource-operators-v1)
      "or" (partial compile-or resource-operators-v1)
      "not" (partial compile-not resource-operators-v1)
      ;; All the subquery operators are unsupported in v1, so we dispatch to a
      ;; function that throws an exception
      "project" unsupported
      "in-result" unsupported
      "select-resources" unsupported
      "select-facts" unsupported
      nil)))

(defn resource-operators-v2
  "Maps v2 resource query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (condp = (string/lower-case op)
    "=" compile-resource-equality
    "~" compile-resource-regexp
    "and" (partial compile-and resource-operators-v2)
    "or" (partial compile-or resource-operators-v2)
    "not" (partial compile-not resource-operators-v2)
    "project" (partial compile-project resource-operators-v2)
    "in-result" (partial compile-in-result :resource resource-operators-v2)
    "select-resources" (partial resource-query->sql resource-operators-v2)
    "select-facts" (partial fact-query->sql fact-operators-v2)
    nil))

(defn fact-operators-v2
  "Maps v2 fact query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (#{">" "<" ">=" "<="} op)
      (partial compile-fact-inequality op)

      (= op "=") compile-fact-equality
      (= op "~") compile-fact-regexp
      ;; We pass this function along so the recursive calls know which set of
      ;; operators/functions to use, depending on the API version.
      (= op "and") (partial compile-and fact-operators-v2)
      (= op "or") (partial compile-or fact-operators-v2)
      (= op "not") (partial compile-not fact-operators-v2)
      (= op "project") (partial compile-project fact-operators-v2)
      (= op "in-result") (partial compile-in-result :fact fact-operators-v2)
      ;; select-resources uses a different set of operators-v2, of course
      (= op "select-resources") (partial resource-query->sql resource-operators-v2)
      (= op "select-facts") (partial fact-query->sql fact-operators-v2))))

(defn compile-term
  [ops [op & args :as term]]
  (when-not (sequential? term)
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must be an array" term))))
  (when-not op
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must contain at least one operator" term))))
  (if-let [f (ops op)]
    (apply f args)
    (throw (IllegalArgumentException. (format "%s is not well-formed: query operator %s is unknown" term op)))))
