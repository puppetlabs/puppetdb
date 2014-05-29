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
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.cheshire :as json]
            [clj-time.coerce :refer [to-timestamp]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defrecord Query [source source-table alias project where subquery? queryable-fields])
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
               :queryable-fields ["certname" "deactivated"]
               :source-table "certnames"
               :alias "nodes"
               :subquery? false
               :source "SELECT name as certname, deactivated FROM certnames"}))

(def resource-params-query
  "Query for the resource-params query, mostly used as a subquery"
  (map->Query {:project {"res_param_resource" :string
                         "res_param_name" :string
                         "res_param_value" :string}
               :queryable-fields ["res_param_resource" "res_param_name" "res_param_value"]
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
               :queryable-fields ["name" "value" "certname"]
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
               :queryable-fields ["certname" "environment" "resource" "type" "title" "tag" "exported" "file" "line" "parameters"]
               :alias "resources"
               :subquery? false
               :source-table "catalog_resources"
               :source "SELECT c.certname, c.hash as catalog, e.name as environment, cr.resource,
                               type, title, tags, exported, file, line, rpc.parameters
                        FROM catalog_resources cr
                             INNER JOIN catalogs c on cr.catalog_id = c.id
                             LEFT OUTER JOIN environments e on c.environment_id = e.id
                             LEFT OUTER JOIN resource_params_cache rpc on rpc.resource = cr.resource"}))

(def reports-query
  "Query for the top level reports entity"
  (map->Query {:project {"hash" :string
                         "certname" :string
                         "puppet_version" :string
                         "report_format" :string
                         "configuration_version" :string
                         "start_time" :timestamp
                         "end_time" :timestamp
                         "receive_time" :timestamp
                         "transaction_uuid" :string
                         "environment" :string
                         "status" :string}
               :queryable-fields ["hash" "certname" "puppet_version" "report_format" "configuration_version" "start_time" "end_time"
                                  "receive_time" "transaction_uuid" "environment" "status"]
               :alias "reports"
               :subquery? false
               :source-table "reports"
               :source "select hash, certname, puppet_version, report_format, configuration_version, start_time, end_time,
                               receive_time, transaction_uuid, environments.name as environment,
                               report_statuses.status as status
                        FROM reports
                             LEFT OUTER JOIN environments on reports.environment_id = environments.id
                             LEFT OUTER JOIN report_statuses on reports.status_id = report_statuses.id"}))

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
              (s/one (s/either s/Str s/Bool s/Int pls/Timestamp) :value)]))

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (cm/match [node]

            [[(:or ">" ">=" "<" "<=") field _]]
            (let [query-context (:query-context (meta node))
                  column-type (get-in query-context [:project field])]
              (when (or (= :string column-type)
                        (= :array column-type))
                (throw (IllegalArgumentException. (format "Query operators >,>=,<,<= are not allowed on field %s" field) ))))

            [[op & _]]
            (when (and (contains? binary-operators op)
                       (binary-operator-checker node))
              (throw (IllegalArgumentException. (format "%s requires exactly two arguments" op))))

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
              (cond
               (= col-type :timestamp)
               (map->EqualsExpression {:column column
                                       :value (to-timestamp value)})

               (= col-type :array)
               (map->ArrayEqualsExpression {:column column
                                            :value value})
               :else
               (map->EqualsExpression {:column column
                                       :value value})))

            [["nil?" column value]]
            (map->NullExpression {:column column
                                  :null? (not value)})

            [["~" column value]]
            (let [col-type (get-in query-rec [:project column])]
              (if (= :string col-type)
                (map->RegexExpression {:column column
                                       :value value})
                (map->ArrayRegexExpression {:table (:source-table query-rec)
                                            :alias (:alias query-rec)
                                            :column column
                                            :value value})))

            [["and" & expressions]]
            (map->AndExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["or" & expressions]]
            (map->OrExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["in" column subquery-expression]]
            (map->InExpression {:column column
                                :subquery (user-node->plan-node query-rec subquery-expression)})

            [["not" expression]] (map->NotExpression {:clause (user-node->plan-node query-rec expression)})

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

(declare push-down-context)

(defn vec?
  "Same as set?/list?/map? but for vectors"
  [x]
  (instance? clojure.lang.IPersistentVector x))

(defn annotate-with-context
  "Add `context` as meta on each `node` that is a vector. This associates the
   the query context assocated to each query clause with it's associated context"
  [context]
  (fn [node state]
    (when (vec? node)
      (cm/match [node]
             [["extract" column
               [subquery-name subquery-expression]]]
             {:node (:node (push-down-context (user-query->logical-obj subquery-name) subquery-expression))
              :cut true
              :state state}

             :else
             {:node (vary-meta node assoc :query-context context)
              :state state}))))

(defn validate-query-fields
  "Add an error message to `state` if the field is not available for querying
   by the associated query-context"
  [node state]
  (cm/match [node]
            [[(:or "=" "~" ">" "<" "<=" ">=") field _]]
            (let [query-context (:query-context (meta node))
                  queryable-fields (:queryable-fields query-context)]
              (when (and (not (vec? field))
                         (not (contains? (set queryable-fields) field)))
                {:node node
                 :state (conj state (format "'%s' is not a queryable object for %s, known queryable objects are %s"
                                            field
                                            (:alias query-context)
                                            (json/generate-string queryable-fields)))}))
            :else nil))

(defn push-down-context
  "Pushes the top level query context down to each query node, throws IllegalArgumentException
   if any unrecognized fields appear in the query"
  [context user-query]
  (let [{annotated-query :node
         errors :state} (zip/pre-order-visit (zip/tree-zipper user-query)
                                             []
                                             [(annotate-with-context context) validate-query-fields])]
    (when (seq errors)
      (throw (IllegalArgumentException. (str/join \newline errors))))

    annotated-query))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compile-user-query->sql
  "Given a user provided query and a Query instance, convert the
   user provided query to SQL and extract the parameters, to be used
   in a prepared statement"
  [query-rec user-query & [{:keys [count?] :as paging-options}]]
  (let [{:keys [plan params]} (->> user-query
                                   (push-down-context query-rec)
                                   expand-user-query
                                   (convert-to-plan query-rec)
                                   extract-all-params)
        sql (plan->sql plan)
        paged-sql (if paging-options
                    (jdbc/paged-sql (plan->sql plan) paging-options)
                    sql)
        result-query {:results-query (apply vector paged-sql params)}]
    (if count?
      (assoc result-query :count-query (apply vector (jdbc/count-sql sql) params))
      result-query)))
