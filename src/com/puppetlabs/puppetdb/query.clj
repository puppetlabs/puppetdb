;; ## SQL query compiler
;;
;; The query compile operates in a multi-step process. Compilation begins with
;; one of the `foo-query->sql` functions. The job of these functions is
;; basically to call `compile-term` on the first term of the query to get back
;; the "compiled" form of the query, and then to turn that into a complete SQL
;; query.
;;
;; The compiled form of a query consists of a map with two keys: `where`
;; and `params`. The `where` key contains SQL for querying that
;; particular predicate, written in such a way as to be suitable for placement
;; after a `WHERE` clause in the database. `params` contains, naturally, the
;; parameters associated with that SQL expression. For instance, a resource
;; query for `["=" ["node" "name"] "foo.example.com"]` will compile to:
;;
;;     {:where "certname_catalogs.certname = ?"
;;      :params ["foo.example.com"]}
;;
;; The `where` key is then inserted into a template query to return
;; the final result as a string of SQL code.
;;
;; The compiled query components can be combined by operators such as `AND` or
;; `OR`, which return the same sort of structure. Operators which accept other
;; terms as their arguments are responsible for compiling their arguments
;; themselves. To facilitate this, those functions accept as their first
;; argument a map from operator to compile function. This allows us to have a
;; different set of operators for resources and facts, or v1 and v2 resource
;; queries, while still sharing the implementation of the operators themselves.
;;
;; Other operators include the subquery operators, `in`, `extract`, and
;; `select-resources` or `select-facts`. The `select-foo` operators implement
;; subqueries, and are simply implemented by calling their corresponding
;; `foo-query->sql` function, which means they return a complete SQL query
;; rather than the compiled query map. The `extract` function knows how to
;; handle that, and is the only place those queries are allowed as arguments.
;; `extract` is used to select a particular column from the subquery. The
;; sibling operator to `extract` is `in`, which checks that the value of
;; a certain column from the table being queried is in the result set returned
;; by `extract`. Composed, these three operators provide a complete subquery
;; facility. For example, consider this fact query:
;;
;;     ["and"
;;      ["=" ["fact" "name"] "ipaddress"]
;;      ["in" "certname"
;;       ["extract" "certname"
;;        ["select-resources" ["and"
;;                             ["=" "type" "Class"]
;;                             ["=" "title" "apache"]]]]]]
;;
;; This will perform a query (via `select-resources`) for resources matching
;; `Class[apache]`. It will then pick out the `certname` from each of those,
;; and match against the `certname` of fact rows, returning those facts which
;; have a corresponding entry in the results of `select-resources` and which
;; are named `ipaddress`. Effectively, the semantics of this query are "find
;; the ipaddress of every node with Class[apache]".
;;
;; The resulting SQL from the `foo-query->sql` functions selects all the
;; columns. Thus consumers of those functions may need to wrap that query with
;; another `SELECT` to pull out only the desired columns. Similarly for
;; applying ordering constraints.
;;
(ns com.puppetlabs.puppetdb.query
  (:require [clojure.string :as string])
  (:use [com.puppetlabs.utils :only [parse-number keyset]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-as-numeric sql-array-query-string sql-regexp-match sql-regexp-array-match]]
        [com.puppetlabs.jdbc :only [valid-jdbc-query? paged-sql]]
        [clojure.core.match :only [match]]))

(defn compile-term
  "Compile a single query term, using `ops` as the set of legal operators. This
  function basically just checks that the operator is known, and then
  dispatches to the function implementing it."
  [ops [op & args :as term]]
  (when-not (sequential? term)
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must be an array" term))))
  (when-not op
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must contain at least one operator" term))))
  (if-let [f (ops op)]
    (apply f args)
    (throw (IllegalArgumentException. (format "%s is not well-formed: query operator '%s' is unknown" term op)))))

(defn compile-boolean-operator*
  "Compile a term for the boolean operator `op` (AND or OR) applied to
  `terms`. This is accomplished by compiling each of the `terms` and then just
  joining their `where` terms with the operator. The params are just
  concatenated."
  [op ops & terms]
  {:pre  [(every? coll? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [compiled-terms (map #(compile-term ops %) terms)
        params (mapcat :params compiled-terms)
        query  (->> (map :where compiled-terms)
                    (map #(format "(%s)" %))
                    (string/join (format " %s " (string/upper-case op))))]
    {:where  query
     :params params}))

(def compile-and
  (partial compile-boolean-operator* "and"))

(def compile-or
  (partial compile-boolean-operator* "or"))

(defn negate-term*
  "Compiles `term` and returns the negated version of the query."
  [ops term]
  {:pre  [(sequential? term)]
   :post [(string? (:where %))]}
  (let [compiled-term (compile-term ops term)
        query (format "NOT (%s)" (:where compiled-term))]
    (assoc compiled-term :where query)))

(defn compile-not-v1
  "Compile a v1 NOT operator, applied to `terms`. This term is true if *every*
  argument term is false. The compilation is effectively to apply an OR
  operation to the terms, and then NOT that result."
  [ops & terms]
  {:pre  [(every? sequential? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str "not requires at least one term"))))
  (negate-term* ops (cons "or" terms)))

(defn compile-not-v2
  "Compile a v2 NOT operator, applied to `term`. This term simply negates the
  value of `term`. Basically this function just serves as error checking for
  `negate-term*`."
  [ops & terms]
  {:post [(string? (:where %))]}
  (when-not (= (count terms) 1)
    (throw (IllegalArgumentException. (format "'not' takes exactly one argument, but %d were supplied" (count terms)))))
  (negate-term* ops (first terms)))

;; This map's keys are the queryable fields for facts, and the values are the
;;  corresponding table names where the fields reside
(def fact-columns {"certname" "certname_facts"
                   "name"     "certname_facts"
                   "value"    "certname_facts"})

;; This map's keys are the queryable fields for resources, and the values are the
;;  corresponding table names where the fields reside
(def resource-columns {"certname"   "certname_catalogs"
                       "catalog"    "catalog_resources"
                       "resource"   "catalog_resources"
                       "type"       "catalog_resources"
                       "title"      "catalog_resources"
                       "tags"       "catalog_resources"
                       "exported"   "catalog_resources"
                       "sourcefile" "catalog_resources"
                       "sourceline" "catalog_resources"})

;; This map's keys are the queryable fields for nodes, and the values are the
;;  corresponding table names where the fields reside
(def node-columns {"name"         "certnames"
                   "deactivated"  "certnames"})

(defn column-map->sql
  "Helper function that converts one of our column maps to a SQL string suitable
  for use in a SELECT"
  [col-map]
  (string/join ", "
    (for [[field table] col-map]
      (str table "." field))))


(def queryable-fields
  {:resource (keyset resource-columns)
   :fact     (keyset fact-columns)
   :node     (keyset node-columns)})

(def subquery->type
  {"select-resources" :resource
   "select-facts" :fact})

(defn compile-extract
  "Compile an `extract` operator, selecting the given `field` from the compiled
  result of `subquery`, which must be a kind of `select` operator."
  [ops field subquery]
  {:pre [(string? field)
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (let [[subselect & params] (compile-term ops subquery)
        subquery-type (subquery->type (first subquery))]
    (when-not subquery-type
      (throw (IllegalArgumentException. (format "The argument to extract must be a select operator, not '%s'" (first subquery)))))
    (when-not (get-in queryable-fields [subquery-type field])
      (throw (IllegalArgumentException. (format "Can't extract unknown %s field '%s'. Acceptable fields are: %s" (name subquery-type) field (string/join ", " (sort (queryable-fields subquery-type)))))))
    {:where (format "SELECT r1.%s FROM (%s) r1" field subselect)
     :params params}))

(defn compile-in
  "Compile an `in` operator, selecting rows for which the value of
  `field` appears in the result given by `subquery`, which must be an `extract`
  composed with a `select`."
  [kind ops field subquery]
  {:pre [(string? field)
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (when-not (get-in queryable-fields [kind field])
    (throw (IllegalArgumentException. (format "Can't match on unknown %s field '%s' for 'in'. Acceptable fields are: %s" (name kind) field (string/join ", " (sort (queryable-fields kind)))))))
  (when-not (= (first subquery) "extract")
    (throw (IllegalArgumentException. (format "The subquery argument of 'in' must be an 'extract', not '%s'" (first subquery)))))
  (let [{:keys [where] :as compiled-subquery} (compile-term ops subquery)]
    (assoc compiled-subquery :where (format "%s IN (%s)" field where))))

(defn resource-query->sql
  "Compile a resource query, returning a vector containing the SQL and
  parameters for the query. All resource columns are selected, and no order is applied."
  ([ops query] (resource-query->sql ops query {}))
  ([ops query paging-options]
   {:post [valid-jdbc-query? %]}
    (let [{:keys [where params]} (compile-term ops query)
          sql (format "SELECT %s FROM catalog_resources JOIN certname_catalogs USING(catalog) WHERE %s" (column-map->sql resource-columns) where)
          result-columns (map keyword (keys resource-columns))
          paged-sql (paged-sql sql result-columns paging-options)]
      (apply vector paged-sql params))))

(defn fact-query->sql
  "Compile a fact query, returning a vector containing the SQL and parameters
  for the query. All fact columns are selected, and no order is applied."
  [ops query]
  {:post [valid-jdbc-query? %]}
  (let [{:keys [where params]} (compile-term ops query)
        sql (format "SELECT %s FROM certname_facts WHERE %s" (column-map->sql fact-columns) where)]
    (apply vector sql params)))

(defn node-query->sql
  "Compile a node query, returning a vector containing the SQL and parameters
  for the query. All node columns are selected, and no order is applied."
  [ops query]
  {:post [valid-jdbc-query? %]}
  (let [{:keys [where params]} (compile-term ops query)
        sql (format "SELECT %s FROM certnames WHERE %s" (column-map->sql node-columns) where)]
    (apply vector sql params)))

(defn compile-resource-equality-v2
  "Compile an = operator for a v2 resource query. `path` represents the field
  to query against, and `value` is the value."
  [& [path value :as args]]
  {:post [(map? %)
          (:where %)]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (match [path]
         ;; tag join. Tags are case-insensitive but always lowercase, so
         ;; lowercase the query value.
         ["tag"]
         {:where  (sql-array-query-string "tags")
          :params [(string/lower-case value)]}

         ;; node join.
         ["certname"]
         {:where  "certname_catalogs.certname = ?"
          :params [value]}

         ;; {in,}active nodes.
         [["node" "active"]]
         {
           :where (format "certname_catalogs.certname IN (SELECT name FROM certnames WHERE deactivated IS %s)" (if value "NULL" "NOT NULL"))}

         ;; param joins.
         [["parameter" (name :when string?)]]
         {:where  "catalog_resources.resource IN (SELECT rp.resource FROM resource_params rp WHERE rp.name = ? AND rp.value = ?)"
          :params [name (db-serialize value)]}

         ;; metadata match.
         [(metadata :when #{"catalog" "resource" "type" "title" "tags" "exported" "sourcefile" "sourceline"})]
           {:where  (format "catalog_resources.%s = ?" metadata)
            :params [value]}

         ;; ...else, failure
         :else (throw (IllegalArgumentException.
                       (str path " is not a queryable object for resources")))))

(defn compile-resource-equality-v1
  "Compile an = operator for a v1 resource query. `path` represents the field
  to query against, and `value` is the value. This mostly just defers to
  `compile-resource-equality-v2`, with a little bit of logic to handle the one
  term that differs."
  [& [path value :as args]]
  {:post [(map? %)
          (:where %)]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  ;; We call it "certname" in v2, and ["node" "name"] in v1. If they specify
  ;; ["node" "name"], rewrite it as "certname". But if they specify "certname",
  ;; fail because this is v1.
  (when (= path "certname")
    (throw (IllegalArgumentException. "certname is not a queryable object for resources")))
  (let [path (if (= path ["node" "name"]) "certname" path)]
    (compile-resource-equality-v2 path value)))

(defn compile-resource-regexp
  "Compile an '~' predicate for a resource query, which does regexp matching.
  This is done by leveraging the correct database-specific regexp syntax to
  return only rows where the supplied `path` match the given `pattern`."
  [path pattern]
  {:post [(map? %)
          (:where %)]}
  (match [path]
         ["tag"]
         {:where (sql-regexp-array-match "catalog_resources" "tags")
          :params [pattern]}

         ;; node join.
         ["certname"]
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
  "Compile an = predicate for a fact query. `path` represents the field to
  query against, and `value` is the value."
  [path value]
  {:post [(map? %)
          (:where %)]}
  (match [path]
         ["name"]
         {:where "certname_facts.name = ?"
          :params [value]}

         ["value"]
         {:where "certname_facts.value = ?"
          :params [(str value)]}

         ["certname"]
         {:where "certname_facts.certname = ?"
          :params [value]}

         [["node" "active"]]
         {
          :where (format "certname_facts.certname IN (SELECT name FROM certnames WHERE deactivated IS %s)" (if value "NULL" "NOT NULL"))}
         :else
         (throw (IllegalArgumentException. (str path " is not a queryable object for facts")))))

(defn compile-fact-regexp
  "Compile an '~' predicate for a fact query, which does regexp matching.  This
  is done by leveraging the correct database-specific regexp syntax to return
  only rows where the supplied `path` match the given `pattern`."
  [path pattern]
  {:pre [(string? path)
         (string? pattern)]
   :post [(map? %)
          (string? (:where %))]}
  (let [query (fn [col] {:where (sql-regexp-match col) :params [pattern]})]
    (match [path]
           ["certname"]
           (query "certname_facts.certname")

           ["name"]
           (query "certname_facts.name")

           ["value"]
           (query "certname_facts.value")

           :else (throw (IllegalArgumentException.
                          (str path " is not a valid operand for regexp comparison"))))))

(defn compile-fact-inequality
  "Compile a numeric inequality for a fact query (> < >= <=). The `value` for
  comparison must be either a number or the string representation of a number.
  The value in the database will be cast to a float or an int for comparison,
  or will be NULL if it is neither."
  [op path value]
  {:pre [(string? path)]
   :post [(map? %)
          (string? (:where %))]}
  (if-let [number (parse-number (str value))]
    (match [path]
           ["value"]
           ;; This is like convert_to_numeric(certname_facts.value) > 0.3
           {:where  (format "%s %s ?" (sql-as-numeric "certname_facts.value") op)
            :params [number]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for facts"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(defn compile-node-equality-v1
  "Compile a v1 equality operator for nodes. This can either be for the value of
  a specific fact, or based on node activeness."
  [path value]
  {:post [(map? %)
          (string? (:where %))]}
  (match [path]
         [["fact" (name :when string?)]]
         {:where  "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND cf.value = ?)"
          :params [name (str value)]}
         [["node" "active"]]
         {:where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

         :else (throw (IllegalArgumentException.
                        (str path " is not a queryable object for nodes")))))

(defn compile-node-equality-v2
  "Compile a v2 equality operator for nodes. This can either be for the value of
  a specific fact, or based on node activeness."
  [path value]
  {:post [(map? %)
          (string? (:where %))]}
  (match [path]
         ["name"]
         {:where "certnames.name = ?"
          :params [value]}
         [["fact" (name :when string?)]]
         {:where  "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND cf.value = ?)"
          :params [name (str value)]}
         [["node" "active"]]
         {:where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

         :else (throw (IllegalArgumentException.
                        (str path " is not a queryable object for nodes")))))

(defn compile-node-regexp
  "Compile an '~' predicate for a fact query, which does regexp matching.  This
  is done by leveraging the correct database-specific regexp syntax to return
  only rows where the supplied `path` match the given `pattern`."
  [path pattern]
  {:pre [(string? pattern)]
   :post [(map? %)
          (string? (:where %))]}
  (let [query (fn [col] {:where (sql-regexp-match col) :params [pattern]})]
    (match [path]
           ["name"]
           {:where (sql-regexp-match "certnames.name")
            :params [pattern]}

           [["fact" (name :when string?)]]
           {:where (format "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND %s)" (sql-regexp-match "cf.value"))
            :params [name pattern]}

           :else (throw (IllegalArgumentException.
                          (str path " is not a valid operand for regexp comparison"))))))

(defn compile-node-inequality
  [op path value]
  {:post [(map? %)
          (string? (:where %))]}
  (if-let [number (parse-number (str value))]
    (match [path]
           [["fact" (name :when string?)]]
           {:where  (format "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND %s %s ?)" (sql-as-numeric "cf.value") op)
            :params [name number]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for nodes"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(declare fact-operators-v2)

(defn resource-operators-v1
  "Maps v1 resource query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [unsupported (fn [& args]
                      (throw (IllegalArgumentException. (format "Operator '%s' is not available in v1 resource queries" op))))]
    (condp = (string/lower-case op)
      "=" compile-resource-equality-v1
      "and" (partial compile-and resource-operators-v1)
      "or" (partial compile-or resource-operators-v1)
      "not" (partial compile-not-v1 resource-operators-v1)
      ;; All the subquery operators are unsupported in v1, so we dispatch to a
      ;; function that throws an exception
      "extract" unsupported
      "in" unsupported
      "select-resources" unsupported
      "select-facts" unsupported
      nil)))

(defn resource-operators-v2
  "Maps v2 resource query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (condp = (string/lower-case op)
    "=" compile-resource-equality-v2
    "~" compile-resource-regexp
    "and" (partial compile-and resource-operators-v2)
    "or" (partial compile-or resource-operators-v2)
    "not" (partial compile-not-v2 resource-operators-v2)
    "extract" (partial compile-extract resource-operators-v2)
    "in" (partial compile-in :resource resource-operators-v2)
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
      (= op "not") (partial compile-not-v2 fact-operators-v2)
      (= op "extract") (partial compile-extract fact-operators-v2)
      (= op "in") (partial compile-in :fact fact-operators-v2)
      ;; select-resources uses a different set of operators-v2, of course
      (= op "select-resources") (partial resource-query->sql resource-operators-v2)
      (= op "select-facts") (partial fact-query->sql fact-operators-v2))))

(defn node-operators-v1
  "Maps v1 node query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)
        unsupported (fn [& args]
                      (throw (IllegalArgumentException. (format "Operator '%s' is not available in v1 node queries" op))))]
    (cond
      (= op "=") compile-node-equality-v1
      (#{">" "<" ">=" "<="} op) (partial compile-node-inequality op)
      (= op "and") (partial compile-and node-operators-v1)
      (= op "or") (partial compile-or node-operators-v1)
      (= op "not") (partial compile-not-v1 node-operators-v1)
      (#{"~" "extract" "in" "select-resources" "select-facts"} op) unsupported)))

(defn node-operators-v2
  "Maps v1 node query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= op "=") compile-node-equality-v2
      (= op "~") compile-node-regexp
      (#{">" "<" ">=" "<="} op) (partial compile-node-inequality op)
      (= op "and") (partial compile-and node-operators-v2)
      (= op "or") (partial compile-or node-operators-v2)
      (= op "not") (partial compile-not-v1 node-operators-v2)
      (= op "extract") (partial compile-extract node-operators-v2)
      (= op "in") (partial compile-in :node node-operators-v2)
      (= op "select-resources") (partial resource-query->sql resource-operators-v2)
      (= op "select-facts") (partial fact-query->sql fact-operators-v2))))
