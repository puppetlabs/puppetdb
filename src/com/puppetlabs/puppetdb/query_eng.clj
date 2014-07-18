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
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.kitchensink.core :as ks]
            [com.puppetlabs.puppetdb.query.paging :as paging]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defrecord Query [source source-table alias project where subquery? queryable-fields])
(defrecord BinaryExpression [operator column value])
(defrecord RegexExpression [column value])
(defrecord ArrayRegexExpression [table alias column value])
(defrecord NullExpression [column null?])
(defrecord ArrayBinaryExpression [column value])
(defrecord InExpression [column subquery])
(defrecord AndExpression [clauses])
(defrecord OrExpression [clauses])
(defrecord NotExpression [clause])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queryable Entities

(def nodes-query
  "Query for nodes entities, mostly used currently for subqueries"
  (map->Query {:project {"certname" :string
                         "deactivated" :string
                         "facts_environment" :string
                         "report_environment" :string
                         "catalog_environment" :string
                         "facts_timestamp" :timestamp
                         "report_timestamp" :timestamp
                         "catalog_timestamp" :timestamp}
               :queryable-fields ["certname" "deactivated" "facts-environment" "report-environment" "catalog-environment" "facts-timestamp"
                                  "report-timestamp" "catalog-timestamp"]
               :source-table "certnames"
               :alias "nodes"
               :subquery? false
               :source "SELECT certnames.name as certname,
                               certnames.deactivated,
                               catalogs.timestamp AS catalog_timestamp,
                               fs.timestamp AS facts_timestamp,
                               reports.end_time AS report_timestamp,
                               catalog_environment.name AS catalog_environment,
                               facts_environment.name AS facts_environment,
                               reports_environment.name AS report_environment
                       FROM certnames
                            LEFT OUTER JOIN catalogs ON certnames.name = catalogs.certname
                            LEFT OUTER JOIN factsets as fs ON certnames.name = fs.certname
                            LEFT OUTER JOIN reports ON certnames.name = reports.certname
                             AND reports.hash
                               IN (SELECT report FROM latest_reports)
                            LEFT OUTER JOIN environments AS catalog_environment ON catalog_environment.id = catalogs.environment_id
                            LEFT OUTER JOIN environments AS facts_environment ON facts_environment.id = fs.environment_id
                            LEFT OUTER JOIN environments AS reports_environment ON reports_environment.id = reports.environment_id"}))

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
                         "value" :coercible-string
                         "certname" :string
                         "environment" :string}
               :alias "facts"
               :queryable-fields ["name" "value" "certname" "environment"]
               :source-table "facts"
               :subquery? false
               :source "SELECT fs.certname,
                               fp.path as name,
                               COALESCE(fv.value_string,
                                        cast(fv.value_integer as text),
                                        cast(fv.value_boolean as text),
                                        cast(fv.value_float as text),
                                        '') as value,
                               env.name as environment
                        FROM factsets fs
                             INNER JOIN facts as f on fs.id = f.factset_id
                             INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                             INNER JOIN fact_paths as fp on fv.path_id = fp.id
                             LEFT OUTER JOIN environments as env on fs.environment_id = env.id
                        WHERE fp.depth = 0"}))

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
                         "line" :number
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
                         "report_format" :number
                         "configuration_version" :string
                         "start_time" :timestamp
                         "end_time" :timestamp
                         "receive_time" :timestamp
                         "transaction_uuid" :string
                         "environment" :string
                         "status" :string}
               :queryable-fields ["hash" "certname" "puppet-version" "report-format" "configuration-version" "start-time" "end-time"
                                  "receive-time" "transaction-uuid" "environment" "status"]
               :alias "reports"
               :subquery? false
               :source-table "reports"
               :source "select hash, certname, puppet_version, report_format, configuration_version, start_time, end_time,
                               receive_time, transaction_uuid, environments.name as environment,
                               report_statuses.status as status
                        FROM reports
                             LEFT OUTER JOIN environments on reports.environment_id = environments.id
                             LEFT OUTER JOIN report_statuses on reports.status_id = report_statuses.id"}))

(def report-events-query
  "Query for the top level reports entity"
  (map->Query {:project {"certname" :string
                         "configuration_version" :string
                         "run_start_time" :timestamp
                         "run_end_time" :timestamp
                         "report_receive_time" :timestamp
                         "report" :string
                         "status" :string
                         "timestamp" :timestamp
                         "resource_type" :string
                         "resource_title" :string
                         "property" :string
                         "new_value" :string
                         "old_value" :string
                         "message" :string
                         "file" :string
                         "line" :number
                         "containment_path" :array
                         "containing_class" :string
                         "environment" :string}
               :queryable-fields ["message" "old-value" "report-receive-time" "run-end-time" "containment-path"
                                  "certname" "run-start-time" "timestamp" "configuration-version" "new-value"
                                  "resource-title" "status" "property" "resource-type" "line" "environment"
                                  "containing-class" "file" "report" "latest-report?"]
               :alias "events"
               :subquery? false
               :source-table "resource_events"
               :source "select reports.certname, reports.configuration_version, reports.start_time as run_start_time,
                               reports.end_time as run_end_time, reports.receive_time as report_receive_time, report, status,
                               timestamp, resource_type, resource_title, property, new_value, old_value, message, file, line,
                               containment_path, containing_class, environments.name as environment
                        FROM resource_events
                             JOIN reports ON resource_events.report = reports.hash
                             LEFT OUTER JOIN environments on reports.environment_id = environments.id"}))

(def latest-report-query
  "Usually used as a subquery of reports"
  (map->Query {:project {"latest_report_hash" :string}
               :queryable-fields ["latest_report_hash"]
               :alias "latest_report"
               :subquery? false
               :source-table "latest_report"
               :source "SELECT latest_reports.report as latest_report_hash
                        FROM latest_reports"}))

(def environments-query
  "Basic environments query, more useful when used with subqueries"
  (map->Query {:project {"name" :string}
               :queryable-fields ["name"]
               :alias "environments"
               :subquery? false
               :source-table "environments"
               :source "SELECT name
                        FROM environments"}))

(def factsets-query
  "Query for the top level facts query"
  (map->Query {:project {"path" :string
                         "value" :variable
                         "certname" :string
                         "timestamp" :timestamp
                         "environment" :string
                         "type" :string}
               :alias "factsets"
               :queryable-fields ["certname" "environment" "timestamp"]
               :source-table "factsets"
               :subquery? false
               :source
               "select fact_paths.path, timestamp,
                               COALESCE(fact_values.value_string, CAST(fact_values.value_integer as text),
                                        CAST(fact_values.value_float as text), CAST(fact_values.value_boolean as text)) as value,
                               factsets.certname, environments.name as environment, value_types.type
                        FROM factsets
                             INNER JOIN facts on factsets.id = facts.factset_id
                             INNER JOIN fact_values on facts.fact_value_id = fact_values.id
                             INNER JOIN fact_paths on fact_values.path_id = fact_paths.id
                             INNER JOIN value_types on fact_paths.value_type_id = value_types.id
                             LEFT OUTER JOIN environments on factsets.environment_id = environments.id
                        ORDER BY factsets.certname"}))

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
    (let [alias (:alias query)
          has-where? (boolean (:where query))]
      (parenthize
       (:subquery? query)
       (format "SELECT %s FROM ( %s ) AS %s %s %s"
               (str/join ", " (map #(format "%s.%s" alias %) (keys (:project query))))
               (:source query)
               (:alias query)
               (if has-where?
                 "WHERE"
                 "")
               (if has-where?
                 (-plan->sql (:where query))
                 "")))))

  InExpression
  (-plan->sql [expr]
    (format "%s in %s"
            (:column expr)
            (-plan->sql (:subquery expr))))

  BinaryExpression
  (-plan->sql [expr]
    (format "%s %s %s"
            (-plan->sql (:column expr))
            (:operator expr)
            (-plan->sql (:value expr))))

  ArrayBinaryExpression
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
    (parenthize true (str/join " AND " (map -plan->sql (:clauses expr)))))

  OrExpression
  (-plan->sql [expr]
    (parenthize true (str/join " OR " (map -plan->sql (:clauses expr)))))

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
  (or (instance? BinaryExpression node)
      (instance? RegexExpression node)
      (instance? ArrayBinaryExpression node)
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
   "select-facts" (assoc facts-query :subquery? true)
   "select-latest-report" (assoc latest-report-query :subquery? true)})

(def binary-operators
  #{"=" ">" "<" ">=" "<=" "~"})

(defn expand-query-node
  "Expands/normalizes the user provided query to a minimal subset of the
   query language"
  [node]
  (cm/match [node]

            [["=" ["node" "active"] value]]
            ["in" "certname"
             ["extract" "certname"
              ["select-nodes"
               ["null?" "deactivated" value]]]]

            [[(op :guard #{"=" "~"}) ["parameter" param-name] param-value]]
            ["in" "resource"
             ["extract" "res_param_resource"
              ["select-params"
               ["and"
                [op "res_param_name" param-name]
                [op "res_param_value" (db-serialize param-value)]]]]]

            [[(op :guard #{"=" "~" ">" "<" "<=" ">="}) ["fact" fact-name] fact-value]]
            ["in" "certname"
             ["extract" "certname"
              ["select-facts"
               ["and"
                ["=" "name" fact-name]
                [op "value" fact-value]]]]]

            [["=" "latest_report?" value]]
            (let [expanded-latest ["in" "report"
                                   ["extract" "latest_report_hash"
                                    ["select-latest-report"]]]]
              (if value
                expanded-latest
                ["not" expanded-latest]))

            [[op (field :guard #{"new_value" "old_value"}) value]]
            [op field (db-serialize value)]

            [["=" field nil]]
            ["null?" (jdbc/dashes->underscores field) true]

            [[op "tag" array-value]]
            [op "tags" (str/lower-case array-value)]

            :else nil))

(def binary-operator-checker
  "A function that will return nil if the query snippet successfully validates, otherwise
   will return a data structure with error information"
  (s/checker [(s/one
               (apply s/either (map s/eq binary-operators))
               :operator)
              (s/one (s/either s/Str
                               [(s/one s/Str :nested-field)
                                (s/one s/Str :nested-value)])
                     :field)
              (s/one (s/either s/Str s/Bool s/Int pls/Timestamp) :value)]))

(defn vec?
  "Same as set?/list?/map? but for vectors"
  [x]
  (instance? clojure.lang.IPersistentVector x))

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (cm/match [node]

            [[(:or ">" ">=" "<" "<=") field _]]
            (let [query-context (:query-context (meta node))
                  column-type (get-in query-context [:project field])]
              (when-not (or (vec? field)
                            (contains? #{:coercible-string :number :timestamp} column-type))
                (throw (IllegalArgumentException. (format "Query operators >,>=,<,<= are not allowed on field %s" field) ))))

            ;;This validation check is added to fix a failing facts
            ;;test. The facts test is checking that you can't submit
            ;;an empty query, but a ["=" ["node" "active"] true]
            ;;clause is automatically added, causing the empty query
            ;;to fall through to the and clause. Adding this here to
            ;;pass the test, but better validation for all clauses
            ;;needs to be added
            [["and" & clauses]]
            (when (some (complement seq) clauses)
              (throw (IllegalArgumentException. "[] is not well-formed: queries must contain at least one operator")))

            ;;Facts is doing validation against nots only having 1
            ;;clause, adding this here to fix that test, need to make
            ;;another pass once other validations are known
            [["not" & clauses]]
            (when (not= 1 (count clauses))
              (throw (IllegalArgumentException. (format "'not' takes exactly one argument, but %s were supplied" (count clauses)))))

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
               (map->BinaryExpression {:operator "="
                                       :column column
                                       :value (to-timestamp value)})

               (= :coercible-string col-type)
               (map->BinaryExpression (if (number? value)
                                        {:operator "="
                                         :column (su/sql-as-numeric column)
                                         :value value}
                                        {:operator "="
                                         :column column
                                         :value (str value)}))

               (= col-type :array)
               (map->ArrayBinaryExpression {:column column
                                            :value value})

               (= col-type :number)
               (map->BinaryExpression {:operator "="
                                       :column column
                                       :value (if (string? value)
                                                (ks/parse-number (str value))
                                                value)})

               :else
               (map->BinaryExpression {:operator "="
                                       :column column
                                       :value value})))

            [[(op  :guard #{">" "<" ">=" "<="}) column value]]
            (let [col-type (get-in query-rec [:project column])]
              (if value
                (map->BinaryExpression {:operator op
                                        :column (if (= :coercible-string col-type)
                                                  (su/sql-as-numeric column)
                                                  column)
                                        :value  (if (= :timestamp col-type)
                                                  (to-timestamp value)
                                                  (ks/parse-number (str value)))})
                (throw (IllegalArgumentException.
                        (format "Value %s must be a number for %s comparison." value op)))))


            [["null?" column value]]
            (map->NullExpression {:column column
                                  :null? value})

            [["~" column value]]
            (let [col-type (get-in query-rec [:project column])]
              (if (= :array col-type)
                (map->ArrayRegexExpression {:table (:source-table query-rec)
                                            :alias (:alias query-rec)
                                            :column column
                                            :value value})
                (map->RegexExpression {:column column
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
              [subquery-name & subquery-expression]]]
            (assoc (user-query->logical-obj subquery-name)
              :project {column nil}
              :where (when (seq subquery-expression)
                       (user-node->plan-node (user-query->logical-obj subquery-name) (first subquery-expression))))
            :else nil))

(defn convert-to-plan
  "Converts the given `user-query` to a query plan that can later be converted into
   a SQL statement"
  [query-rec user-query]
  (assoc query-rec :where (user-node->plan-node query-rec user-query)))

(declare push-down-context)

(defn annotate-with-context
  "Add `context` as meta on each `node` that is a vector. This associates the
   the query context assocated to each query clause with it's associated context"
  [context]
  (fn [node state]
    (when (vec? node)
      (cm/match [node]
                [["extract" column
                  [subquery-name subquery-expression]]]
                (let [subquery-expr (push-down-context (user-query->logical-obj subquery-name) subquery-expression)
                      nested-qc (:query-context (meta subquery-expr))
                      queryable-fields (:queryable-fields nested-qc)]

                  {:node (vary-meta ["extract" column
                                     (vary-meta [subquery-name subquery-expr]
                                                assoc :query-context nested-qc)]
                                    assoc :query-context nested-qc)

                   ;;Might need to revisit this once all of the
                   ;;validations are known, but the :cut true will no
                   ;;longer traverse the tree, which was causing
                   ;;problems with the validation below when it was
                   ;;included in the validate-query-fields function
                   :state (if (and (not (vec? column))
                                   (not (contains? (set queryable-fields) column)))
                            (conj state (format "Can't extract unknown '%s' field '%s'. Acceptable fields are: %s"
                                                (:alias nested-qc)
                                                column
                                                (json/generate-string queryable-fields)))

                            state)
                   :cut true})

                :else
                (when (instance? clojure.lang.IMeta node)
                  {:node (vary-meta node assoc :query-context context)
                   :state state})))))

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

            [["in" field & _]]
            (let [query-context (:query-context (meta node))
                  queryable-fields (:queryable-fields query-context)]
              (when (and (not (vec? field))
                         (not (contains? (set queryable-fields) field)))
                {:node node
                 :state (conj state (format "Can't match on unknown '%s' field '%s' for 'in'. Acceptable fields are: %s"
                                            (:alias query-context)
                                            field
                                            (json/generate-string queryable-fields)))}))

            :else nil))

(defn dashes-to-underscores
  "Convert field names with dashes to underscores"
  [node state]
  (cm/match [node]
            [[(op :guard binary-operators) (field :guard string?) value]]
            {:node (with-meta [op (jdbc/dashes->underscores field) value]
                     (meta node))
             :state state}
            :else {:node node :state state}))

(defn ops-to-lower
  "Lower cases operators (such as and/or"
  [node state]
  (cm/match [node]
            [[op & stmt-rest]]
            {:node (with-meta (vec (cons (str/lower-case op) stmt-rest))
                     (meta node))
             :state state}
            :else {:node node :state state}))

(defn push-down-context
  "Pushes the top level query context down to each query node, throws IllegalArgumentException
   if any unrecognized fields appear in the query"
  [context user-query]
  (let [{annotated-query :node
         errors :state} (zip/pre-order-visit (zip/tree-zipper user-query)
                                             []
                                             [(annotate-with-context context) validate-query-fields dashes-to-underscores ops-to-lower])]
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
  (when paging-options
    (paging/validate-order-by! (map keyword (:queryable-fields query-rec)) paging-options))
  (let [{:keys [plan params]} (->> user-query
                                   (push-down-context query-rec)
                                   expand-user-query
                                   (convert-to-plan query-rec)
                                   extract-all-params)
        sql (plan->sql plan)
        paged-sql (if paging-options
                    (jdbc/paged-sql sql paging-options)
                    sql)
        result-query {:results-query (apply vector paged-sql params)}]
    (if count?
      (assoc result-query :count-query (apply vector (jdbc/count-sql sql) params))
      result-query)))
