(ns com.puppetlabs.puppetdb.query-eng
  (:require [clojure.string :as str]
            [com.puppetlabs.puppetdb.zip :as zip]
            [com.puppetlabs.puppetdb.scf.storage-utils :as su]
            [clojure.string :as str]
            [com.puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize]]
            [clojure.core.match :as cm]
            [fast-zip.visit :as zv]
            [com.puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [com.puppetlabs.jdbc :as jdbc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defrecord Query [source source-table alias project where subquery?])
(defrecord EqualsExpression [column value])
(defrecord RegexExpression [column value])
(defrecord ArrayRegexExpression [table alias column value])
(defrecord NullExpression [column null?])
(defrecord ArrayEqualsExpression [column value])
(defrecord InExpression [column subquery])
(defrecord AndExpression [clauses])
(defrecord OrExpression [clauses])
(defrecord NotExpression [clause])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queryable Entities

(def nodes-query
  "Query for nodes entities, mostly used currently for subqueries"
  (map->Query {:project {"certname" :string
                         "deactivated" :string}
               :source-table "certnames"
               :alias "nodes"
               :subquery? false
               :source "SELECT name as certname, deactivated FROM certnames"}))

(def resource-params-query
  "Query for the resource-params query, mostly used as a subquery"
  (map->Query {:project {"res_param_resource" :string
                         "res_param_name" :string
                         "res_param_value" :string}
               :source-table "resource_params"
               :alias "resource_params"
               :subquery? false
               :source "select resource as res_param_resource, name as res_param_name, value as res_param_value from resource_params"}))

(def facts-query
  "Query for the top level facts query"
  (map->Query {:project {"name" :string
                         "value" :string
                         "certname" :string}
               :alias "facts"
               :source-table "certname_facts"
               :subquery? false
               :source "select cf.certname, cf.name, cf.value, env.name as environment
                        FROM certname_facts cf
                             INNER JOIN certname_facts_metadata cfm on cf.certname = cfm.certname
                             LEFT OUTER JOIN environments as env on cfm.environment_id = env.id"}))

(def resources-query
  "Query for the top level resource entity"
  (map->Query {:project {"certname" :string
                         "environment" :string
                         "resource" :string
                         "type" :string
                         "title" :string
                         "tags" :array
                         "exported" :string
                         "file" :string
                         "line" :string
                         "parameters" :string}
               :alias "resources"
               :subquery? false
               :source-table "catalog_resources"
               :source (str  "SELECT c.certname, c.hash as catalog, e.name as environment, cr.resource,
                                       type, title, tags, exported, file, line, rpc.parameters
                                FROM catalog_resources cr
                                     INNER JOIN catalogs c on cr.catalog_id = c.id
                                     LEFT OUTER JOIN environments e on c.environment_id = e.id
                                     LEFT OUTER JOIN resource_params_cache rpc on rpc.resource = cr.resource")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion from plan to SQL

(defprotocol SQLGen
  (-plan->sql [query] "Given the `query` plan node, convert it to a SQL string"))

(defn parenthize
  "Wrap `s` in parens if `wrap-in-parens?`"
  [wrap-in-parens? s]
  (if wrap-in-parens?
    (str " ( " s " ) ")
    s))

(extend-protocol SQLGen
  Query
  (-plan->sql [query]
    (let [alias (:alias query)]
      (parenthize
       (:subquery? query)
       (format "SELECT %s FROM ( %s ) AS %s WHERE %s"
               (str/join ", " (map #(format "%s.%s" alias %) (keys (:project query))))
               (:source query)
               (:alias query)
               (-plan->sql (:where query))))))

  InExpression
  (-plan->sql [expr]
    (format "%s in %s"
            (:column expr)
            (-plan->sql (:subquery expr))))

  EqualsExpression
  (-plan->sql [expr]
    (format "%s = %s"
            (-plan->sql (:column expr))
            (-plan->sql (:value expr))))

  ArrayEqualsExpression
  (-plan->sql [expr]
    (su/sql-array-query-string (:column expr)))

  RegexExpression
  (-plan->sql [expr]
    (su/sql-regexp-match (:column expr)))

  ArrayRegexExpression
  (-plan->sql [expr]
    (su/sql-regexp-array-match (:table expr) (:alias expr) (:column expr)))

  NullExpression
  (-plan->sql [expr]
    (format "%s IS %s"
            (-plan->sql (:column expr))
            (if (:null? expr)
              "NULL"
              "NOT NULL")))

  AndExpression
  (-plan->sql [expr]
    (str/join " AND " (map -plan->sql (:clauses expr))))

  OrExpression
  (-plan->sql [expr]
    (str/join " OR " (map -plan->sql (:clauses expr))))

  NotExpression
  (-plan->sql [expr]
    (format "NOT ( %s )" (-plan->sql (:clause expr))))

  Object
  (-plan->sql [obj]
    (str obj)))

(defn plan->sql
  "Convert `query` to a SQL string"
  [query]
  (-plan->sql query))

(defn binary-expression?
  "True if the plan node is a binary expression"
  [node]
  (or (instance? EqualsExpression node)
      (instance? RegexExpression node)
      (instance? ArrayEqualsExpression node)
      (instance? ArrayRegexExpression node)))

(defn extract-params
  "Extracts the node's expression value, puts it in state
   replacing it with `?`, used in a prepared statement"
  [node state]
  (when (binary-expression? node)
    {:node (assoc node :value "?")
     :state (conj state (:value node))}))

(defn extract-all-params
  "Zip through the query plan, replacing each user provided query paramter with '?'
   and return the parameters as a vector"
  [plan]
  (let [{:keys [node state]} (zip/post-order-visit (zip/tree-zipper plan)
                                                   []
                                                   [extract-params])]
    {:plan node
     :params state}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; User Query - functions/transformations of the user defined query
;;;              language

(def user-query->logical-obj
  "Keypairs of the stringified subquery keyword (found in user defined queries) to the
   appropriate plan node"
  {"select-nodes" (assoc nodes-query :subquery? true)
   "select-resources" (assoc resources-query :subquery? true)
   "select-params" (assoc resource-params-query :subquery? true)
   "select-facts" (assoc facts-query :subquery? true)})

(defn expand-query-node
  "Expands/normalizes the user provided query to a minimal subset of the
   query language"
  [node]
  (cm/match [node]

            [["=" ["node" "active"] value]]
            ["in" "certname"
             ["extract" "certname"
              ["select-nodes"
               ["nil?" "deactivated" (not value)]]]]


            [["=" ["parameter" param-name] param-value]]
            ["in" "resource"
             ["extract" "res_param_resource"
              ["select-params"
               ["and"
                ["=" "res_param_name" param-name]
                ["=" "res_param_value" (db-serialize param-value)]]]]]

            [[op "tag" array-value]]
            [op "tags" array-value]

            :else nil))

(def binary-operators
  #{"=" ">" "<" ">=" "<=" "~"})

(def binary-operator-checker
  "A function that will return nil if the query snippet successfully validates, otherwise
   will return a data structure with error information"
  (s/checker [(s/one
               (apply s/either (map s/eq binary-operators))
               :operator)
              (s/one s/Str :field)
              (s/one (s/either s/Str s/Bool) :value)]))

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (cm/match [node]

            [[op & _]]
            (when (and (contains? binary-operators op)
                       (binary-operator-checker node))
              (throw (IllegalArgumentException. (format "%s requires exactly two string arguments" op))))

            :else nil))

(defn expand-user-query
  "Expands/translates the query from a user provided one to a
   normalized query that only contains our lower-level operators.
   Things like [node active] will be expanded into a full
   subquery (via the `in` and `extract` operators)"
  [user-query]
  (:node (zip/post-order-transform (zip/tree-zipper user-query)
                                   [expand-query-node validate-binary-operators])))

(defn user-node->plan-node
  "Create a query plan for `node` in the context of the given query (as `query-rec`)"
  [query-rec node]
  (cm/match [node]
            [["=" column value]]
            (let [col-type (get-in query-rec [:project column])]
              (if (= col-type :string)
                (EqualsExpression. column value)
                (ArrayEqualsExpression. column value)))

            [["nil?" column value]]
            (NullExpression. column (not value))

            [["~" column value]]
            (let [col-type (get-in query-rec [:project column])]
              (if (= :string col-type)
                (RegexExpression. column value)
                (ArrayRegexExpression. (:source-table query-rec)
                                       (:alias query-rec)
                                       column
                                       value)))

            [["and" & expressions]]
            (AndExpression. (map #(user-node->plan-node query-rec %) expressions))

            [["or" & expressions]]
            (OrExpression. (map #(user-node->plan-node query-rec %) expressions))

            [["in" column subquery-expression]]
            (InExpression. column (user-node->plan-node query-rec subquery-expression))

            [["not" expression]] (NotExpression. (user-node->plan-node query-rec expression))

            [["extract" column
              [subquery-name subquery-expression]]]
            (assoc (user-query->logical-obj subquery-name)
              :project {column nil}
              :where (user-node->plan-node (user-query->logical-obj subquery-name) subquery-expression))
            :else nil))

(defn convert-to-plan
  "Converts the given `user-query` to a query plan that can later be converted into
   a SQL statement"
  [query-rec user-query]
  (assoc query-rec :where (user-node->plan-node query-rec user-query)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compile-user-query->sql
  "Given a user provided query and a Query instance, convert the
   user provided query to SQL and extract the parameters, to be used
   in a prepared statement"
  [query-rec user-query & [{:keys [count?] :as paging-options}]]
  (let [{:keys [plan params]} (->> user-query
                                   expand-user-query
                                   (convert-to-plan query-rec)
                                   extract-all-params)
        sql (if paging-options
              (jdbc/paged-sql (plan->sql plan) paging-options)
              (plan->sql plan))
        result-query {:results-query (apply vector sql params)}]
    (if count?
      (assoc result-query :count-query (apply vector (jdbc/count-sql sql) params))
      result-query)))
