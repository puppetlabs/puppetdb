(ns puppetlabs.puppetdb.query-eng.engine
  (:require [clojure.string :as str]
            [puppetlabs.puppetdb.zip :as zip]
            [puppetlabs.puppetdb.scf.storage-utils :as su]
            [puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize]]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.facts :as facts]
            [clojure.core.match :as cm]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.query.paging :as paging]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defrecord Query [source source-table alias project where subquery? queryable-fields entity supports-extract?])
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
               :queryable-fields ["certname" "deactivated" "facts_environment"
                                  "report_environment" "catalog_environment"
                                  "facts_timestamp" "report_timestamp"
                                  "catalog_timestamp"]
               :source-table "certnames"
               :alias "nodes"
               :subquery? false
               :supports-extract? true
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
               :supports-extract? true
               :source "select resource as res_param_resource, name as res_param_name, value as res_param_value from resource_params"}))

(def fact-paths-query
  "Query for the resource-params query, mostly used as a subquery"
  (map->Query {:project {"type" :string
                         "path" :path}
               :queryable-fields ["type" "path"]
               :source-table "fact_paths"
               :alias "fact_paths"
               :subquery? false
               :supports-extract? true
               :source "SELECT path, type
                        FROM fact_paths fp
                        INNER JOIN value_types vt ON fp.value_type_id=vt.id
                        WHERE fp.value_type_id != 5"}))

(def facts-query
  "Query structured facts."

  (map->Query {:project {"path" :string
                         "value" :multi
                         "depth" :integer
                         "certname" :string
                         "environment" :string
                         "value_integer" :number
                         "value_float" :number
                         "name" :string
                         "type" :string}
               :alias "facts"
               :queryable-fields ["name" "certname" "environment" "value"]
               :source-table "facts"
               :entity :facts
               :subquery? false
               :supports-extract? false
               :source
               "SELECT fs.certname,
                       fp.path as path,
                       fp.name as name,
                       fp.depth as depth,
                       fv.value_integer as value_integer,
                       fv.value_float as value_float,
                       fv.value_hash,
                       fv.value_string,
                       COALESCE(fv.value_string,
                                fv.value_json,
                                cast(fv.value_boolean as text)) as value,
                       vt.type as type,
                       env.name as environment
                FROM factsets fs
                  INNER JOIN facts as f on fs.id = f.factset_id
                  INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                  INNER JOIN fact_paths as fp on fv.path_id = fp.id
                  INNER JOIN value_types as vt on vt.id=fv.value_type_id
                  LEFT OUTER JOIN environments as env on fs.environment_id = env.id
                WHERE depth = 0"}))

(def fact-contents-query
  "Query for fact nodes"
  (map->Query {:project {"path" :path
                         "value" :multi
                         "certname" :string
                         "name" :string
                         "environment" :string
                         "value_integer" :number
                         "value_float" :number
                         "type" :string}
               :alias "fact_nodes"
               :queryable-fields ["path" "value" "certname" "environment" "name"]
               :source-table "facts"
               :subquery? false
               :supports-extract? false
               :source
               "SELECT fs.certname,
                       fp.path,
                       fp.name as name,
                       COALESCE(fv.value_string,
                                CAST(fv.value_boolean as text)) as value,
                       fv.value_string,
                       fv.value_hash,
                       fv.value_integer as value_integer,
                       fv.value_float as value_float,
                       env.name as environment,
                       vt.type
                FROM factsets fs
                  INNER JOIN facts as f on fs.id = f.factset_id
                  INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                  INNER JOIN fact_paths as fp on fv.path_id = fp.id
                  INNER JOIN value_types as vt on fp.value_type_id = vt.id
                  LEFT OUTER JOIN environments as env on fs.environment_id = env.id
                WHERE fp.value_type_id != 5"}))

(def reports-query
  "Query for the resource-events entity"
  (map->Query {:project {"certname" :string
                         "environment" :string
                         "puppet_version" :string
                         "report_format" :number
                         "configuration_version" :string
                         "old_value" :string
                         "new_value" :string
                         "timestamp" :timestamp
                         "containment_path" :string
                         "event_status" :string
                         "file" :string
                         "resource_type" :string
                         "resource_title" :string
                         "start_time" :timestamp
                         "end_time" :timestamp
                         "receive_time" :timestamp
                         "property" :string
                         "line" :number
                         "hash" :string
                         "message" :string
                         "transaction_uuid" :string
                         "status" :string}
               :queryable-fields ["certname" "environment" "puppet_version"
                                  "report_format" "configuration_version"
                                  "start_time" "end_time" "transaction_uuid"
                                  "status" "hash" "receive_time"]
               :alias "reports"
               :subquery? false
               :entity :reports
               :source-table "reports"
               :source "select reports.hash,
                       reports.certname,
                       reports.puppet_version,
                       reports.report_format,
                       reports.configuration_version,
                       reports.start_time,
                       reports.end_time,
                       reports.receive_time,
                       reports.transaction_uuid,
                       environments.name as environment,
                       report_statuses.status as status,
                       re.report,
                       re.status as event_status,
                       re.timestamp,
                       re.resource_type,
                       re.resource_title,
                       re.property,
                       re.new_value,
                       re.old_value,
                       re.message,
                       re.file,
                       re.line,
                       re.containment_path,
                       re.containing_class
                       FROM reports
                       INNER JOIN resource_events re on reports.hash=re.report
                       LEFT OUTER JOIN environments on reports.environment_id = environments.id
                       LEFT OUTER JOIN report_statuses on reports.status_id = report_statuses.id"}))

(def catalog-query
  "Query for the top level catalogs entity"
  (map->Query {:project {"version" :string
                         "environment" :string
                         "transaction_uuid" :string
                         "hash" :string
                         "name" :string
                         "producer_timestamp" :timestamp
                         "resource" :string
                         "type" :string
                         "title" :string
                         "tags" :string
                         "exported" :string
                         "file" :string
                         "line" :string
                         "parameters" :string
                         "source_type" :string
                         "source_title" :string
                         "target_type" :string
                         "target_title" :string
                         "relationship" :string}

               :queryable-fields ["version" "environment" "transaction_uuid"
                                  "producer_timestamp" "hash" "name"]
               :alias "catalogs"
               :subquery? false
               :source-table "catalogs"
               :source "select c.catalog_version as version,
                       c.certname,
                       c.hash,
                       transaction_uuid,
                       e.name as environment,
                       c.certname as name,
                       c.producer_timestamp,
                       cr.resource,
                       cr.type,
                       cr.title,
                       cr.tags,
                       cr.exported,
                       cr.file,
                       cr.line,
                       rpc.parameters,
                       null as source_type,
                       null as source_title,
                       null as target_type,
                       null as target_title,
                       null as relationship
                       from catalogs c
                       left outer join environments e on c.environment_id = e.id
                       left outer join catalog_resources cr ON c.id=cr.catalog_id
                       inner join resource_params_cache rpc on rpc.resource=cr.resource

                       UNION ALL

                       select c.catalog_version as version,
                       c.certname,
                       c.hash,
                       transaction_uuid,
                       e.name as environment,
                       c.certname as name,
                       c.producer_timestamp,
                       null as resource,
                       null as type,
                       null as title,
                       null as tags,
                       null as exported,
                       null as file,
                       null as line,
                       null as parameters,
                       sources.type as source_type,
                       sources.title as source_title,
                       targets.type as target_type,
                       targets.title as target_title,
                       edges.type as relationship
                       FROM catalogs c
                       left outer join environments e on c.environment_id = e.id
                       INNER JOIN edges ON c.certname = edges.certname
                       INNER JOIN catalog_resources sources
                       ON edges.source = sources.resource AND sources.catalog_id=c.id
                       INNER JOIN catalog_resources targets
                       ON edges.target = targets.resource AND targets.catalog_id=c.id
                       order by certname"}))
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
               :supports-extract? true
               :source-table "catalog_resources"
               :source "SELECT c.certname, c.hash as catalog, e.name as environment, cr.resource,
                               type, title, tags, exported, file, line, rpc.parameters
                        FROM catalog_resources cr
                             INNER JOIN catalogs c on cr.catalog_id = c.id
                             LEFT OUTER JOIN environments e on c.environment_id = e.id
                             LEFT OUTER JOIN resource_params_cache rpc on rpc.resource = cr.resource"}))

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
               :queryable-fields ["message" "old_value" "report_receive_time" "run_end_time" "containment_path"
                                  "certname" "run_start_time" "timestamp" "configuration_version" "new_value"
                                  "resource_title" "status" "property" "resource_type" "line" "environment"
                                  "containing_class" "file" "report" "latest_report?"]
               :alias "events"
               :subquery? false
               :supports-extract? true
               :source-table "resource_events"
               :source "select reports.certname,
                       reports.configuration_version,
                       reports.start_time as run_start_time,
                       reports.end_time as run_end_time,
                       reports.receive_time as report_receive_time,
                       report,
                       status,
                       timestamp,
                       resource_type,
                       resource_title,
                       property,
                       new_value,
                       old_value,
                       message,
                       file,
                       line,
                       containment_path,
                       containing_class,
                       environments.name as environment
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
               :supports-extract? true
               :source "SELECT latest_reports.report as latest_report_hash
                        FROM latest_reports"}))

(def environments-query
  "Basic environments query, more useful when used with subqueries"
  (map->Query {:project {"name" :string}
               :queryable-fields ["name"]
               :alias "environments"
               :subquery? false
               :supports-extract? true
               :source-table "environments"
               :source "SELECT name
                        FROM environments"}))

(def factsets-query
  "Query for the top level facts query"
  (map->Query {:project {"path" :string
                         "hash" :string
                         "value" :variable
                         "certname" :string
                         "timestamp" :timestamp
                         "value_float" :number
                         "value_integer" :number
                         "environment" :string
                         "producer_timestamp" :timestamp
                         "type" :string}
               :alias "factsets"
               :queryable-fields ["certname" "environment" "timestamp"
                                  "producer_timestamp" "hash"]
               :entity :factsets
               :source-table "factsets"
               :subquery? false
               :supports-extract? false
               :source "SELECT fact_paths.path, timestamp,
                               COALESCE(fact_values.value_string,
                                        fact_values.value_json,
                                        CAST(fact_values.value_boolean as text)) as value,
                               fact_values.value_integer as value_integer,
                               fact_values.value_float as value_float,
                               factsets.certname,
                               factsets.hash,
                               factsets.producer_timestamp,
                               environments.name as environment,
                               value_types.type
                        FROM factsets
                             INNER JOIN facts on factsets.id = facts.factset_id
                             INNER JOIN fact_values on facts.fact_value_id = fact_values.id
                             INNER JOIN fact_paths on fact_values.path_id = fact_paths.id
                             INNER JOIN value_types on fact_paths.value_type_id = value_types.id
                             LEFT OUTER JOIN environments on factsets.environment_id = environments.id
                        WHERE depth = 0
                        ORDER BY factsets.certname"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion from plan to SQL

(defn maybe-vectorize-string
  [arg]
  (if (vector? arg) arg [arg]))

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
               (str/join ", " (map #(format "%s.%s" alias %) (sort (keys (:project query)))))
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
    (format "(%s) in %s"
            (str/join "," (sort (:column expr)))
            (-plan->sql (:subquery expr))))

  BinaryExpression
  (-plan->sql [expr]
    (str/join " OR "
              (map
               #(format "%s %s %s"
                        (-plan->sql %1)
                        (:operator expr)
                        (-plan->sql %2))
               (maybe-vectorize-string (:column expr))
               (maybe-vectorize-string (:value expr)))))

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
  "Zip through the query plan, replacing each user provided query parameter with '?'
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
  {"select_nodes" (assoc nodes-query :subquery? true)
   "select_resources" (assoc resources-query :subquery? true)
   "select_params" (assoc resource-params-query :subquery? true)
   "select_facts" (assoc facts-query :subquery? true)
   "select-latest-report" (assoc latest-report-query :subquery? true)
   "select-fact-contents" (assoc fact-contents-query :subquery? true)})

(def binary-operators
  #{"=" ">" "<" ">=" "<=" "~"})

(defn expand-query-node
  "Expands/normalizes the user provided query to a minimal subset of the
  query language"
  [node]
  (cm/match [node]

            [[(op :guard #{"=" "<" ">" "<=" ">="}) "value" (value :guard #(number? %))]]
            ["or" [op "value_integer" value] [op "value_float" value]]

            [[(op :guard #{"=" "~" ">" "<" "<=" ">="}) "value" value]]
            (when (= :facts (get-in (meta node) [:query-context :entity]))
              ["and" ["=" "depth" 0] [op "value" value]])

            [["=" ["node" "active"] value]]
            ["in" "certname"
             ["extract" "certname"
              ["select_nodes"
               ["null?" "deactivated" value]]]]

            [[(op :guard #{"=" "~"}) ["parameter" param-name] param-value]]
            ["in" "resource"
             ["extract" "res_param_resource"
              ["select_params"
               ["and"
                [op "res_param_name" param-name]
                [op "res_param_value" (db-serialize param-value)]]]]]

            [[(op :guard #{"=" "~"}) ["fact" fact-name] (fact-value :guard #(or (string? %)
                                                                                (instance? Boolean %)))]]
            ["in" "certname"
             ["extract" "certname"
              ["select_facts"
               ["and"
                ["=" "name" fact-name]
                [op "value" fact-value]]]]]

            [[(op :guard #{"=" ">" "<" "<=" ">="}) ["fact" fact-name] fact-value]]
            (if-not (number? fact-value)
              (throw (IllegalArgumentException. (format "Operator '%s' not allowed on value '%s'" op fact-value)))
              ["in" "certname"
               ["extract" "certname"
                ["select_facts"
                 ["and"
                  ["=" "name" fact-name]
                  ["or"
                   [op "value_float" fact-value]
                   [op "value_integer" fact-value]]]]]])

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
              (s/one (s/either [(s/either s/Str s/Int)]
                               s/Str s/Bool s/Int pls/Timestamp Double)
                     :value)]))

(defn vec?
  "Same as set?/list?/map? but for vectors"
  [x]
  (instance? clojure.lang.IPersistentVector x))

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (let [query-context (:query-context (meta node))]
    (cm/match [node]

              [[(:or ">" ">=" "<" "<=") field _]]
              (let [col-type (get-in query-context [:project field])]
                (when-not (or (vec? field)
                              (contains? #{:number :timestamp :multi}
                                         col-type))
                  (throw (IllegalArgumentException. (format "Query operators >,>=,<,<= are not allowed on field %s" field)))))

              [["~>" field _]]
              (let [col-type (get-in query-context [:project field])]
                (when-not (contains? #{:path} col-type)
                  (throw (IllegalArgumentException. (format "Query operator ~> is not allowed on field %s" field)))))

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

              :else nil)))

(defn expand-user-query
  "Expands/translates the query from a user provided one to a
  normalized query that only contains our lower-level operators.
  Things like [node active] will be expanded into a full
  subquery (via the `in` and `extract` operators)"
  [user-query]
  (:node (zip/post-order-transform (zip/tree-zipper user-query)
                                   [expand-query-node validate-binary-operators])))

(declare user-node->plan-node)

(defn subquery-expression?
  "Returns true if expr is a subquery expression"
  [expr]
  (contains? (ks/keyset user-query->logical-obj)
             (first expr)))

(defn create-extract-node
  "Returns a `query-rec` that has the correct projection for the given
  `column-list`. Updating :project causes the select in the SQL query
  to be modified. Setting :late-project does not affect the SQL, but
  includes in the information for later removing the columns."
  [query-rec column-list expr]
  (let [project-map (zipmap column-list (repeat (count column-list) nil))]
    (if (or (nil? expr)
            (not (subquery-expression? expr)))
      (let [qr (assoc query-rec :where (user-node->plan-node query-rec expr))]
        (if (:supports-extract? query-rec)
          (assoc qr :project project-map)
          (assoc qr :late-project project-map)))
      (let [[subquery-name & subquery-expression] expr]
        (assoc (user-query->logical-obj subquery-name)
          :project project-map
          :where (when (seq subquery-expression)
                   (user-node->plan-node (user-query->logical-obj subquery-name)
                                         (first subquery-expression))))))))

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

               (= col-type :array)
               (map->ArrayBinaryExpression {:column column
                                            :value value})

               (= col-type :number)
               (map->BinaryExpression {:operator "="
                                       :column column
                                       :value (if (string? value)
                                                (ks/parse-number (str value))
                                                value)})

               (= col-type :path)
               (map->BinaryExpression {:operator "="
                                       :column column
                                       :value (facts/factpath-to-string value)})

               (= col-type :multi)
               (map->BinaryExpression {:operator "="
                                       :column (str column "_hash")
                                       :value (hash/generic-identity-hash value)})

               :else
               (map->BinaryExpression {:operator "="
                                       :column column
                                       :value value})))

            [[(op :guard #{">" "<" ">=" "<="}) column value]]
            (let [col-type (get-in query-rec [:project column])]
              (if value
                (case col-type
                  :multi
                  (map->BinaryExpression {:operator op
                                          :column ["value_integer" "value_float"]
                                          :value (if (number? value) [value value]
                                                     (map ks/parse-number [value value]))})

                  (map->BinaryExpression {:operator op
                                          :column column
                                          :value  (if (= :timestamp col-type)
                                                    (to-timestamp value)
                                                    (ks/parse-number (str value)))}))
                (throw (IllegalArgumentException.
                        (format "Value %s must be a number for %s comparison." value op)))))


            [["null?" column value]]
            (map->NullExpression {:column column
                                  :null? value})

            [["~" column value]]
            (let [col-type (get-in query-rec [:project column])]
              (case col-type
                :array
                (map->ArrayRegexExpression {:table (:source-table query-rec)
                                            :alias (:alias query-rec)
                                            :column column
                                            :value value})

                :multi
                (map->RegexExpression {:column (str column "_string")
                                       :value value})

                (map->RegexExpression {:column column
                                       :value value})))

            [["~>" column value]]
            (let [col-type (get-in query-rec [:project column])]
              (case col-type
                :path
                (map->RegexExpression {:column column
                                       :value (facts/factpath-regexp-to-regexp value)})))

            [["and" & expressions]]
            (map->AndExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["or" & expressions]]
            (map->OrExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["in" column subquery-expression]]
            (map->InExpression {:column (maybe-vectorize-string column)
                                :subquery (user-node->plan-node query-rec subquery-expression)})

            [["not" expression]] (map->NotExpression {:clause (user-node->plan-node query-rec expression)})

            [["extract" column expr]]
            (create-extract-node query-rec (maybe-vectorize-string column) expr)

            :else nil))



(defn convert-to-plan
  "Converts the given `user-query` to a query plan that can later be converted into
  a SQL statement"
  [query-rec user-query]
  (let [where (user-node->plan-node query-rec user-query)]
    (if (instance? Query (user-node->plan-node query-rec user-query))
      where
      (assoc query-rec :where where))))

(declare push-down-context)

(defn validate-query-operation-fields
  "Checks if query operation contains allowed fields. Returns error
  message string if some of the fields are invalid.

  Error-action and error-context parameters help in formatting different error messages."
  [field allowed-fields query-name error-action error-context]
  (let [invalid-fields (remove (set allowed-fields) (ks/as-collection field))]
    (when (> (count invalid-fields) 0)
      (format "%s unknown '%s' %s '%s'%s. Acceptable fields are: %s"
              error-action
              query-name
              (if (> (count invalid-fields) 1) "fields:" "field")
              (str/join "', '" invalid-fields)
              (if (empty? error-context) "" (str " " error-context))
              (json/generate-string allowed-fields)))))

(defn annotate-with-context
  "Add `context` as meta on each `node` that is a vector. This associates the
  the query context assocated to each query clause with it's associated context"
  [context]
  (fn [node state]
    (when (vec? node)
      (cm/match [node]
                [["extract" column
                  [(subquery-name :guard (set (keys user-query->logical-obj))) subquery-expression]]]
                (let [subquery-expr (push-down-context (user-query->logical-obj subquery-name) subquery-expression)
                      nested-qc (:query-context (meta subquery-expr))
                      column-validation-message (validate-query-operation-fields
                                                 column
                                                 (:queryable-fields nested-qc)
                                                 (:alias nested-qc)
                                                 "Can't extract" "")]

                  {:node (vary-meta ["extract" column
                                     (vary-meta [subquery-name subquery-expr]
                                                assoc :query-context nested-qc)]
                                    assoc :query-context nested-qc)

                   ;;Might need to revisit this once all of the
                   ;;validations are known, but the :cut true will no
                   ;;longer traverse the tree, which was causing
                   ;;problems with the validation below when it was
                   ;;included in the validate-query-fields function
                   :state (if column-validation-message
                            (conj state column-validation-message)
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

            ; This validation is only for top-level extract operator
            ; For in-extract operator validation, please see annotate-with-context function
            [["extract" field & _]]
            (let [query-context (:query-context (meta node))
                  queryable-fields (:queryable-fields query-context)
                  column-validation-message (validate-query-operation-fields
                                              field
                                              queryable-fields
                                              (:alias query-context)
                                              "Can't extract" "")]
              (when column-validation-message
                {:node node
                 :state (conj state column-validation-message)}))

            [["in" field & _]]
            (let [query-context (:query-context (meta node))
                  column-validation-message (validate-query-operation-fields
                                             field
                                             (:queryable-fields query-context)
                                             (:alias query-context)
                                             "Can't match on" "for 'in'")]
              (when column-validation-message
                {:node node
                 :state (conj state column-validation-message)}))

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
  "Lower cases operators (such as and/or)."
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
                                             [(annotate-with-context context)
                                              validate-query-fields
                                              dashes-to-underscores ops-to-lower])]
    (when (seq errors)
      (throw (IllegalArgumentException. (str/join \newline errors))))

    annotated-query))

(defn augment-paging-options
  "Specially augmented paging options to include handling the cases where name
  and certname may be part of the ordering."
  [{:keys [order_by] :as paging-options} entity]
  (if (or (not (contains? #{:factsets} entity)) (nil? order_by))
    paging-options
    (let [[to-dissoc to-append] (case entity
                                  :factsets  [nil
                                              [[:certname :ascending]]])
          to-prepend (filter #(not (= to-dissoc (first %))) order_by)]
      (assoc paging-options :order_by (concat to-prepend to-append)))))

(defn basic-project
  "Returns a function will remove non-projected columns if projections is specified."
  [projections]
  (if (seq projections)
    #(select-keys % projections)
    identity))

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
        entity (:entity query-rec)
        augmented-paging-options (augment-paging-options paging-options entity)
        query-params (if (contains? #{:factsets :reports} entity)
                       (concat params params)
                       params)
        sql (plan->sql plan)
        paged-sql (jdbc/paged-sql sql augmented-paging-options entity)
        result-query {:results-query (apply vector paged-sql query-params)
                      :projections (map keyword (keys (:late-project plan)))}]
    (if count?
      (assoc result-query :count-query (apply vector (jdbc/count-sql entity sql) query-params))
      result-query)))
