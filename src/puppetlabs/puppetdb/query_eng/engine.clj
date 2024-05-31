(ns puppetlabs.puppetdb.query-eng.engine
  (:require [clojure.core.match :as cm]
            [clojure.set :as set :refer [map-invert]]
            [clojure.string :as str]
            [puppetlabs.i18n.core :refer [tru trs]]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [honey.sql.helpers :as hsql]
            [honey.sql.pg-ops :as pgop] ;; require to enable postgres ops
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng.parse :as parse]
            [puppetlabs.puppetdb.query.common :refer [bad-query-ex]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.utils.string-formatter :as formatter]
            [puppetlabs.puppetdb.scf.storage-utils :as su]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.time :as t]
            [puppetlabs.puppetdb.zip :as zip]
            [schema.core :as s]
            [clojure.walk :as walk])
  (:import
   (clojure.lang ExceptionInfo)
   [org.postgresql.util PGobject]))

;; Queries must opt-in to the drop-joins optimization, and even then,
;; it's not yet necessarily safe if the query has top-level
;; aggregates.  Before opting in, we should feel confident that
;; removing any one the query's top-level joins won't affect the
;; result set in a material way.  Ignoring the aggregates question,
;; this should be true (for example) for queries that only have
;; top-level left-joins (:left-join), but might not be true for
;; queries with other joins like :inner-join or :join, unless say, the
;; right side is known to have exactly one matching row for any row on
;; the left in all cases (and so it's the same as a left-join).

(def always-enable-drop-unused-joins?
  "When set to true, act as if the opimization has been requested for
  every query.  This is only intended for testing.  Will be set to
  true if the environment variable
  PDB_QUERY_OPTIMIZE_DROP_UNUSED_JOINS is set to \"always\" at
  startup."
  (= "always" (System/getenv "PDB_QUERY_OPTIMIZE_DROP_UNUSED_JOINS")))

(def enable-drop-unused-joins-by-default?
  "When true, enable the opimization whenever a query doesn't set the
  optimize_drop_unused_joins parameter.  This will be true if
  PDB_QUERY_OPTIMIZE_DROP_UNUSED_JOINS is unset or is set to
  \"by-default\" at startup, and will be false if it is set to
  \"by-request\"."
  (let [v (System/getenv "PDB_QUERY_OPTIMIZE_DROP_UNUSED_JOINS")]
    (case v
      "always" true
      "by-default" true
      "by-request" false
      (nil "") true
      (throw
       (Exception. (trs "Invalid PDB_QUERY_OPTIMIZE_DROP_UNUSED_JOINS setting {0}"
                        (pr-str v)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(def field-schema (s/conditional keyword? s/Keyword
                                 map? {:select s/Any s/Any s/Any}
                                 #(= :raw (first %)) [(s/one (s/eq :raw) "raw") (s/one s/Str "sql")]
                                 vector? [s/Any]
                                 :else [s/Keyword]))

; The use of 'column' here is a misnomer. This refers to the result of
; a query and the top level keys used should match up to query entities
; and the values of 'columns', 'local-column', and 'foreign-column'
; should match up to projections of their respective query entities
(def certname-relations
  {"factsets" {:columns ["certname"]}
   "reports" {:columns ["certname"]}
   "package-inventory" {:columns ["certname"]}
   "inventory" {:columns ["certname"]}
   "catalogs" {:columns ["certname"]}
   "catalog_input_contents" {:columns ["certname"]}
   "catalog_inputs" {:columns ["certname"]}
   "nodes" {:columns ["certname"]}
   "facts" {:columns ["certname"]}
   "fact_contents" {:columns ["certname"]}
   "events" {:columns ["certname"]}
   "edges" {:columns ["certname"]}
   "resources" {:columns ["certname"]}
   "certname_fact_expiration" {:columns ["certid"]}})

(def type-coercion-matrix
  {:string {:numeric (su/sql-cast :int)
            :boolean (su/sql-cast :bool)
            :string keyword
            :jsonb-scalar (su/sql-cast :jsonb)
            :timestamp (su/sql-cast :timestamptz)}
   :jsonb-scalar {:numeric (su/jsonb-scalar-cast "numeric")
                  :jsonb-scalar keyword
                  :boolean (comp (su/sql-cast :boolean) (su/sql-cast :text))
                  :string (su/jsonb-scalar-cast "text")}
   :encoded-path {:encoded-path keyword}
   :path-array {:path-array keyword}
   :json {:json keyword}
   :boolean {:string (su/sql-cast :text)
             :boolean keyword}
   :numeric {:string (su/sql-cast :text)
             :numeric keyword}
   :timestamp {:string (su/sql-cast :text)
               :timestamp keyword}
   :array {:array keyword}
   :queryable-json {:queryable-json keyword}})

(defn convert-type
  [column from to]
  ((-> type-coercion-matrix from to) column))


(def ast-path-schema [(s/cond-pre s/Str s/Int)])

(def column-schema
  "Column information: [\"value\" {:type :string :field fv.value_string ...}]"
  {:type s/Keyword :field field-schema s/Any s/Any})

(def projection-schema
  "Named projection: [\"value\" {:type :string :field fv.value_string ...}]"
  [(s/one s/Str "name") (s/one column-schema "column")])

(s/defrecord Query
    ;; Optional fields:
    ;;
    ;;   depends - set of fields that this field depends on, i.e. if
    ;;     this field is included in the query (e.g. via extract),
    ;;     then the dependencies must be included in the projections
    ;;     too.  cf. include-projection-dependencies.  Note that right
    ;;     now, ["function" ...] calls are not examined with respect
    ;;     to dependencies.
    ;;
    ;;   join-deps - must refer to the name if there's only a name, or
    ;;     the alias when there is one, i.e. the dep for :factsets is
    ;;     just :factsets, but for [:factsets :fs] is :fs.
    ;;
    ;;   queryable? - is a query allowed to refer to this field?
    ;;
    ;;   unprojectable? - is the field not a candidate for extraction?
    ;;
    [projections :- {s/Str column-schema}
     selection
     ;; This should be just the top level "from" tables for the query.
     source-tables :- #{s/Keyword}
     alias where subquery? entity call
     group-by limit offset order-by])


(s/defrecord FnBinaryExpression
  [operator :- s/Keyword
   args :- [s/Str]
   function
   value])

(s/defrecord BinaryExpression
    [operator :- s/Keyword
     column :- column-schema
     value])

(s/defrecord InArrayExpression
    [column :- column-schema
     value])

(s/defrecord RegexExpression
    [column :- column-schema
     value])

(s/defrecord ArrayRegexExpression
    [column :- column-schema
     value])

(s/defrecord NullExpression
    [column :- column-schema
     null? :- s/Bool])

(s/defrecord ArrayBinaryExpression
    [column :- column-schema
     value])

;; ["=" "path" ["filesystem" 5 "size"]]
(s/defrecord PathArrayMatch
    [column :- column-schema
     path :- ast-path-schema])

;; ["in" "path" [["filesystem" 5 "size"] ["filesystem" 9 "size"]]
(s/defrecord PathArrayAnyMatch
    [column :- column-schema
     paths :- [ast-path-schema]])

;; ["~>" "path" ["filesystem" 5 ".*-errors"]]
(s/defrecord PathArrayRegexMatch
    [column :- column-schema
     path-rx :- ast-path-schema])

(s/defrecord JsonContainsExpression
  [field :- s/Str
   column-data :- column-schema
   value])

(s/defrecord JsonbPathBinaryExpression
    [field :- s/Str
     column-data :- column-schema
     value
     operator :- s/Keyword])

(s/defrecord JsonbScalarRegexExpression
  [field :- s/Str
   column-data :- column-schema
   value])

(s/defrecord InExpression
    [column :- [column-schema]
     ;; May not want this if it's recursive and not just "instance?"
     subquery :- Query])

(defrecord AndExpression [clauses])
(defrecord OrExpression [clauses])
(defrecord NotExpression [clause])
(defrecord FnExpression [function column params args statement])

(def json-agg-row (comp h/json-agg h/row-to-json))

(def numeric-functions
  #{"sum" "avg" "min" "max"})

(def pdb-fns->pg-fns
  {"sum" "sum"
   "avg" "avg"
   "min" "min"
   "max" "max"
   "count" "count"
   "to_string" "to_char"
   "jsonb_typeof" "jsonb_typeof"})

(def pg-fns->pdb-fns
  (map-invert pdb-fns->pg-fns))

(defn hsql-hash-as-str
  [column-kw]
  [:encode [:cast column-kw :bytea] [:inline "hex"]])

(defn hsql-hash-as-href
  [entity parent child]
  [:format
   [:inline (str "/pdb/query/v4/" (name parent) "/%s/" (name child))]
   (if (string? entity)
     [:raw entity]
     entity)])

(defn hsql-uuid-as-str
  [column-keyword]
  [:cast column-keyword :text])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queryable Entities

(def inventory-query
  "Query for inventory"
  (map->Query {::which-query :inventory
               :can-drop-unused-joins? true
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :certnames.certname
                                         :join-deps #{:certnames}}
                             "timestamp" {:type :timestamp
                                          :queryable? true
                                          :field :fs.timestamp
                                          :join-deps #{:fs}}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :environments.environment
                                            :join-deps #{:environments}}
                             "facts" {:type :queryable-json
                                      :projectable-json? true
                                      :queryable? true
                                      :field [:nest [:|| :fs.stable :fs.volatile]]
                                      :join-deps #{:fs}}
                             "trusted" {:type :queryable-json
                                        :projectable-json? true
                                        :queryable? true
                                        :field [:-> [:|| :fs.stable :fs.volatile] [:inline "trusted"]]
                                        :join-deps #{:fs}}}

               :selection {:from [[:factsets :fs]]
                           :left-join [:environments
                                       [:= :fs.environment_id :environments.id]

                                       :certnames
                                       [:= :fs.certname :certnames.certname]]}

               :alias "inventory"
               :relationships certname-relations

               :dotted-fields ["facts\\..*" "trusted\\..*"]
               :entity :inventory
               :subquery? false}))

(def nodes-query-base
  {::which-query :nodes
   :can-drop-unused-joins? true
   :projections {"certname" {:type :string
                             :queryable? true
                             :field :certnames_status.certname
                             :join-deps #{:certnames_status}}
                 "deactivated" {:type :string
                                :queryable? true
                                :field :certnames_status.deactivated
                                :join-deps #{:certnames_status}}
                 "expired" {:type :timestamp
                            :queryable? true
                            :field :certnames_status.expired
                            :join-deps #{:certnames_status}}
                 "facts_environment" {:type :string
                                      :queryable? true
                                      :field :facts_environment.environment
                                      :join-deps #{:facts_environment :fs}}
                 "catalog_timestamp" {:type :timestamp
                                      :queryable? true
                                      :field :catalogs.timestamp
                                      :join-deps #{:catalogs}}
                 "facts_timestamp" {:type :timestamp
                                    :queryable? true
                                    :field :fs.timestamp
                                    :join-deps #{:fs}}
                 "report_timestamp" {:type :timestamp
                                     :queryable? true
                                     :field :reports.end_time
                                     :join-deps #{:certnames :reports}}
                 "latest_report_hash" {:type :string
                                       :queryable? true
                                       :field (hsql-hash-as-str :reports.hash)
                                       :join-deps #{:certnames :reports}}
                 "latest_report_noop" {:type :boolean
                                       :queryable? true
                                       :field :reports.noop
                                       :join-deps #{:certnames :reports}}
                 "latest_report_noop_pending" {:type :boolean
                                               :queryable? true
                                               :field :reports.noop_pending
                                               :join-deps #{:certnames :reports}}
                 "latest_report_status" {:type :string
                                         :queryable? true
                                         :field :report_statuses.status
                                         :join-deps #{:certnames :report_statuses :reports}}
                 "latest_report_corrective_change" {:type :boolean
                                                    :queryable? true
                                                    :field :reports.corrective_change
                                                    :join-deps #{:certnames :reports}}
                 "latest_report_job_id" {:type :string
                                         :queryable? true
                                         :field :reports.job_id
                                         :join-deps #{:certnames :reports}}
                 "cached_catalog_status" {:type :string
                                          :queryable? true
                                          :field :reports.cached_catalog_status
                                          :join-deps #{:certnames :reports}}
                 "catalog_environment" {:type :string
                                        :queryable? true
                                        :field :catalog_environment.environment
                                        :join-deps #{:catalog_environment :catalogs}}
                 "report_environment" {:type :string
                                       :queryable? true
                                       :field :reports_environment.environment
                                       :join-deps #{:reports_environment :reports}}}

   :relationships certname-relations

   :selection {:from [:certnames_status]
               ;; The join names here must match the values in
               ;; :join-deps above, i.e. :foo or [:foo :bar].
               :left-join [:certnames
                           [:= :certnames_status.certname :certnames.certname]

                           :catalogs
                           [:= :certnames_status.certname :catalogs.certname]

                           [:factsets :fs]
                           [:= :certnames_status.certname :fs.certname]

                           :reports
                           [:and
                            [:= :certnames.certname :reports.certname]
                            [:= :certnames.latest_report_id :reports.id]]

                           [:environments :catalog_environment]
                           [:= :catalog_environment.id :catalogs.environment_id]

                           :report_statuses
                           [:= :reports.status_id :report_statuses.id]

                           [:environments :facts_environment]
                           [:= :facts_environment.id :fs.environment_id]

                           [:environments :reports_environment]
                           [:= :reports_environment.id :reports.environment_id]]}

   :source-tables #{:certnames}
   :alias "nodes"
   :subquery? false})

(def nodes-query
  "Query for nodes entities, mostly used currently for subqueries"
  (map->Query nodes-query-base))

(def nodes-query-with-fact-expiration
  "Query for nodes entities, mostly used currently for subqueries"
  ;; These changes are still safe wrt drop-unused-joins, so we leave
  ;; it enabled.
  (map->Query (-> nodes-query-base
                  (assoc ::which-query :nodes-with-fact-expiration)
                  (assoc-in [:projections "expires_facts"]
                            {:type :boolean
                             :queryable? true
                             :field [:coalesce
                                     :certname_fact_expiration.expire
                                     [:inline true]]
                             :join-deps #{:certname_fact_expiration}})
                  (assoc-in [:projections "expires_facts_updated"]
                            {:type :timestamp
                             :queryable? true
                             :field :certname_fact_expiration.updated
                             :join-deps #{:certname_fact_expiration}})
                  (update-in [:selection :left-join]
                             #(conj %
                                    :certname_fact_expiration
                                    [:= :certnames.id :certname_fact_expiration.certid])))))

(def resource-params-query
  "Query for the resource-params query, mostly used as a subquery"
  ;; Don't opt-in to drop-unused-joins; it'd be an expensive no-op
  (map->Query {::which-query :resource-params
               :projections {"res_param_resource" {:type :string
                                                   :queryable? true
                                                   :field (hsql-hash-as-str :resource)}
                             "res_param_name" {:type :string
                                               :queryable? true
                                               :field :name}
                             "res_param_value" {:type :string
                                                :queryable? true
                                                :field :value}}
               :selection {:from [:resource_params]}

               :source-tables #{:resource_params}
               :alias "resource_params"
               :subquery? false}))

(def fact-paths-query
  "Query for the fact-paths query, mostly used as a subquery"
  (map->Query {::which-query :fact-paths
               :can-drop-unused-joins? true
               :projections {"type" {:type :string
                                     :queryable? true
                                     :field :vt.type
                                     :join-deps #{:vt}}
                             "path" {:type :encoded-path
                                     :queryable? true
                                     :field :path
                                     :join-deps #{:fp}}
                             "name" {:type :string
                                     :queryable? true
                                     :field :name
                                     :join-deps #{:fp}}
                             "depth" {:type :numeric
                                      :queryable? true
                                      :unprojectable? true
                                      :field :fp.depth
                                      :join-deps #{:fp}}}
               :selection {:from [[:fact_paths :fp]]
                           :left-join [[:value_types :vt]
                                       [:= :fp.value_type_id :vt.id]]
                           :where [:!= :fp.value_type_id [:inline 5]]}

               :relationships {;; Children - direct
                               "facts" {:columns ["name"]}
                               "fact_contents" {:columns ["path"]}}

               :source-tables #{:fact_paths}
               :alias "fact_paths"
               :subquery? false}))

(def fact-names-query
  ;; Don't opt-in to drop-unused-joins since it can't do anything here
  (map->Query {::which-query :fact-names
               :projections {"name" {:type :string
                                     :queryable? true
                                     :field :name}}
               :selection {:from [[:fact_paths :fp]]
                           :modifiers [:distinct]}
               :source-tables #{:fact_paths}
               :alias "fact_names"
               :subquery? false}))

(def facts-query
  "Query structured facts."
  (map->Query {::which-query :facts
               :can-drop-unused-joins? true
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname
                                         :join-deps #{:fs}}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.environment
                                            :join-deps #{:env}}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fs.key
                                     :join-deps #{:fs}}
                             "value" {:type :jsonb-scalar
                                      :queryable? true
                                      :field :fs.value
                                      :join-deps #{:fs}}}
               ;; Consider rewrite-fact-query when making any changes here
               :selection {:select [:certname :environment_id :key :value]
                           :from [[{:select [:certname :environment_id
                                             :facts.key :facts.value]
                                    :from :factsets
                                    ;; fixme: don't need cross-join?
                                    :cross-join [[[:lateral
                                                   {:union-all
                                                    [{:select :* :from [[[:jsonb_each :stable]]]}
                                                     {:select :* :from [[[:jsonb_each :volatile]]]}]}]
                                                  :facts]]}
                                   :fs]]
                           :left-join [[:environments :env]
                                       [:= :fs.environment_id :env.id]]}
               :relationships (merge certname-relations
                                     {"environments" {:local-columns ["environment"]
                                                      :foreign-columns ["name"]}
                                      "fact_contents" {:columns ["certname" "name"]}})

               :alias "facts"
               :source-tables #{:factsets}
               :entity :facts
               :subquery? false}))

(def fact-contents-core
  (str
   "(select certname, flattened.*"
   "   from factsets fs"
   "   left join lateral ("
   "     with recursive flattened_one (parent_path, parent_types, key, value, type) as ("
   "       select"
   "           array[]::text[], '', facts.*, 's'"
   "       from (select * from jsonb_each(fs.stable)"
   "             union all select * from jsonb_each(fs.volatile)) as facts"
   "       union all"
   ;;        -- jsonb_each().* expands into key and value columns via facts.*
   "         select"
   "             parent_path || flattened_one.key,"
   "             parent_types || flattened_one.type,"
   "             sub_paths.key, sub_paths.value, sub_paths.type"
   "           from flattened_one"
   "           inner join lateral ("
   "             select"
   "               (jsonb_each(value)).*,"
   "               's' as type"
   "             where jsonb_typeof(value) = 'object'"
   "             union all"
   "             select"
   "                 generate_series::text as key,"
   "                 value->generate_series as value,"
   "                 'i' as type"
   "               from generate_series(0, jsonb_array_length(value) - 1)"
   "               where jsonb_typeof(value) = 'array'"
   "           ) as sub_paths on true"
   "     )"
   "     select"
   "         environment_id,"
   "         parent_path || key as path,"
   "         parent_types || type as types,"
   "         coalesce(parent_path[1], key) as name,"
   "         value"
   "       from flattened_one where not jsonb_typeof(value) = any('{\"array\", \"object\"}')"
   "   ) as flattened"
   "   on true)"))

(def fact-contents-query
  "Query for fact nodes"
  (map->Query {::which-query :fact-contents
               :can-drop-unused-joins? true
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :fc.certname
                                         :join-deps #{:fc}}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.environment
                                            :join-deps #{:env}}
                             "path" {:type :path-array
                                     :queryable? true
                                     :field :fc.path
                                     :signature :fc.types
                                     :depends #{"path_types"}
                                     :join-deps #{:fc}}
                             "path_types" {:type :string
                                           :queryable? false
                                           :unprojectable? true
                                           :field :fc.types
                                           :join-deps #{:fc}}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fc.name
                                     :join-deps #{:fc}}
                             "value" {:type :jsonb-scalar
                                      :queryable? true
                                      :field :value
                                      :join-deps #{:fc}}}
               :selection {:from [[[:raw fact-contents-core] :fc]]
                           :left-join [[:environments :env]
                                       [:= :fc.environment_id :env.id]]}

               :relationships (merge certname-relations
                                     {"facts" {:columns ["certname" "name"]}
                                      "environments" {:local-columns ["environment"]
                                                      :foreign-columns ["name"]}})

               :alias "fact_nodes"
               :source-tables #{:factsets :fact_paths}
               :subquery? false}))

(def report-logs-query
  "Query intended to be used by the `/reports/<hash>/logs` endpoint
  used for digging into the logs for a specific report."
  ;; Don't opt-in to drop-unused-joins; it'd be an expensive no-op atm
  (map->Query {::which-query :report-logs
               :projections {"logs" {:type :json
                                     :queryable? false
                                     :field (h/coalesce :logs
                                                        (h/scast :logs_json :jsonb))}
                             "hash" {:type :string
                                     :queryable? true
                                     :unprojectable? true
                                     :field (hsql-hash-as-str :reports.hash)}
                             "type" {:type :string
                                     :queryable? true
                                     :unprojectable? true
                                     :field :reports.report_type}}
               :selection {:from [:reports]}

               :alias "logs"
               :subquery? false
               :entity :reports
               :source-tables #{:reports}}))

(def report-metrics-query
  "Query intended to be used by the `/reports/<hash>/metrics` endpoint
  used for digging into the metrics for a specific report."
  ;; Don't opt-in to drop-unused-joins; it'd be an expensive no-op atm
  (map->Query {::which-query :report-metrics
               :projections {"metrics" {:type :json
                                        :queryable? false
                                        :field (h/coalesce :reports.metrics
                                                           (h/scast :reports.metrics_json :jsonb))}
                             "hash" {:type :string
                                     :queryable? true
                                     :unprojectable? true
                                     :field (hsql-hash-as-str :reports.hash)}
                             "type" {:type :string
                                     :queryable? true
                                     :unprojectable? true
                                     :field :reports.report_type}}
               :selection {:from [:reports]}

               :alias "metrics"
               :subquery? false
               :entity :reports
               :source-tables #{:reports}}))

(def reports-query
  "Query for the reports entity"
  (map->Query
    {::which-query :reports
     :can-drop-unused-joins? true
     :projections
     {"hash" {:type :string
              :queryable? true
              :field (hsql-hash-as-str :reports.hash)
              :join-deps #{:reports}}
      "certname" {:type :string
                  :queryable? true
                  :field :reports.certname
                  :join-deps #{:reports}}
      "noop_pending" {:type :boolean
                      :queryable? true
                      :field :reports.noop_pending
                      :join-deps #{:reports}}
      "puppet_version" {:type :string
                        :queryable? true
                        :field :reports.puppet_version
                        :join-deps #{:reports}}
      "report_format" {:type :numeric
                       :queryable? true
                       :field :reports.report_format
                       :join-deps #{:reports}}
      "configuration_version" {:type :string
                               :queryable? true
                               :field :reports.configuration_version
                               :join-deps #{:reports}}
      "start_time" {:type :timestamp
                    :queryable? true
                    :field :reports.start_time
                    :join-deps #{:reports}}
      "end_time" {:type :timestamp
                  :queryable? true
                  :field :reports.end_time
                  :join-deps #{:reports}}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :reports.producer_timestamp
                            :join-deps #{:reports}}
      "producer" {:type :string
                  :queryable? true
                  :field :producers.name
                  :join-deps #{:producers}}
      "corrective_change" {:type :string
                           :queryable? true
                           :field :reports.corrective_change
                           :join-deps #{:reports}}
      "metrics" {:type :json
                 :queryable? false
                 :field {:select [(h/row-to-json :t)]
                         :from [[{:select
                                  [[(h/coalesce :metrics (h/scast :metrics_json :jsonb)) :data]
                                   [(hsql-hash-as-href (hsql-hash-as-str :hash) :reports :metrics)
                                    :href]]} :t]]}
                 :join-deps #{:reports}}
      "logs" {:type :json
              :queryable? false
              :field {:select [(h/row-to-json :t)]
                      :from [[{:select
                               [[(h/coalesce :logs (h/scast :logs_json :jsonb)) :data]
                                [(hsql-hash-as-href (hsql-hash-as-str :hash) :reports :logs)
                                 :href]]} :t]]}
              :join-deps #{:reports}}
      "receive_time" {:type :timestamp
                      :queryable? true
                      :field :reports.receive_time
                      :join-deps #{:reports}}
      "transaction_uuid" {:type :string
                          :queryable? true
                          :field (hsql-uuid-as-str :reports.transaction_uuid)
                          :join-deps #{:reports}}
      "catalog_uuid" {:type :string
                      :queryable? true
                      :field (hsql-uuid-as-str :reports.catalog_uuid)
                      :join-deps #{:reports}}
      "noop" {:type :boolean
              :queryable? true
              :field :reports.noop
              :join-deps #{:reports}}
      "code_id" {:type :string
                 :queryable? true
                 :field :reports.code_id
                 :join-deps #{:reports}}
      "job_id" {:type :string
                :queryable? true
                :field :reports.job_id
                :join-deps #{:reports}}
      "cached_catalog_status" {:type :string
                               :queryable? true
                               :field :reports.cached_catalog_status
                               :join-deps #{:reports}}
      "environment" {:type :string
                     :queryable? true
                     :field :environments.environment
                     :join-deps #{:environments}}
      "status" {:type :string
                :queryable? true
                :field :report_statuses.status
                :join-deps #{:report_statuses}}
      "type" {:type :string
              :queryable? true
              :field :reports.report_type
              :join-deps #{:reports}}

      "latest_report?" {:type :string
                        :queryable? true
                        :unprojectable? true
                        :join-deps #{}}
      "resource_events" {:type :json
                         :queryable? false
                         :field {:select [(h/row-to-json :event_data)]
                                 :from [[{:select
                                          [[(json-agg-row :t) :data]
                                           [(hsql-hash-as-href (hsql-hash-as-str :hash) :reports :events) :href]]
                                          :from [[{:select
                                                   [:re.status
                                                    :re.timestamp
                                                    :re.resource_type
                                                    :re.resource_title
                                                    :re.property
                                                    :re.corrective_change
                                                    [(h/scast :re.new_value :jsonb)]
                                                    [(h/scast :re.old_value :jsonb)]
                                                    :re.message
                                                    :re.file
                                                    :re.line
                                                    :re.containment_path
                                                    :re.containing_class
                                                    :re.name]
                                                   :from [[:resource_events :re]]
                                                   :where [:= :reports.id :re.report_id]} :t]]}
                                         :event_data]]}
                         :join-deps #{:reports}}}
     :selection {:from [:reports]
                 :left-join [:environments
                             [:= :environments.id :reports.environment_id]

                             :producers
                              [:= :producers.id :reports.producer_id]

                             :report_statuses
                             [:= :reports.status_id :report_statuses.id]]}

     :relationships (merge certname-relations
                           {"environments" {:local-columns ["environment"]
                                            :foreign-columns ["name"]}
                            "producers" {:local-columns ["producer"]
                                         :foreign-columns ["name"]}
                            "events" {:local-columns ["hash"]
                                      :foreign-columns ["report"]}})

     :alias "reports"
     :subquery? false
     :entity :reports
     :source-tables #{:reports}}))

(def catalog-query
  "Query for the top level catalogs entity"
  (map->Query
    {::which-query :catalog
     :can-drop-unused-joins? true
     :projections
     {"version" {:type :string
                 :queryable? true
                 :field :c.catalog_version
                 :join-deps #{:c}}
      "certname" {:type :string
                  :queryable? true
                  :field :c.certname
                  :join-deps #{:c}}
      "hash" {:type :string
              :queryable? true
              :field (hsql-hash-as-str :c.hash)
              :join-deps #{:c}}
      "transaction_uuid" {:type :string
                          :queryable? true
                          :field (hsql-uuid-as-str :c.transaction_uuid)
                          :join-deps #{:c}}
      "catalog_uuid" {:type :string
                      :queryable? true
                      :field (hsql-uuid-as-str :c.catalog_uuid)
                      :join-deps #{:c}}
      "code_id" {:type :string
                 :queryable? true
                 :field :c.code_id
                 :join-deps #{:c}}
      "job_id" {:type :string
                :queryable? true
                :field :c.job_id
                :join-deps #{:c}}
      "environment" {:type :string
                     :queryable? true
                     :field :e.environment
                     :join-deps #{:e}}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :c.producer_timestamp
                            :join-deps #{:c}}
      "producer" {:type :string
                  :queryable? true
                  :field :producers.name
                  :join-deps #{:producers}}
      "resources" {:type :json
                   :queryable? false
                   :field {:select [(h/row-to-json :resource_data)]
                           :from [[{:select [[(json-agg-row :t) :data]
                                             [(hsql-hash-as-href :c.certname :catalogs :resources) :href]]
                                    :from [[{:select [[(hsql-hash-as-str :cr.resource) :resource]
                                                      :cr.type :cr.title :cr.tags :cr.exported :cr.file :cr.line
                                                      [(h/scast :rpc.parameters :json) :parameters]]
                                             :from [[:catalog_resources :cr]]
                                             :join [[:resource_params_cache :rpc]
                                                    [:= :rpc.resource :cr.resource]]
                                             :where [:= :cr.certname_id :certnames.id]}
                                            :t]]}
                                   :resource_data]]}
                   :join-deps #{:c :certnames}}
      "edges" {:type :json
               :queryable? false
               :field {:select [(h/row-to-json :edge_data)]
                       :from [[{:select [[(json-agg-row :t) :data]
                                         [(hsql-hash-as-href :c.certname :catalogs :edges) :href]]
                                :from [[{:select [[:sources.type :source_type] [:sources.title :source_title]
                                                  [:targets.type :target_type] [:targets.title :target_title]
                                                  [:edges.type :relationship]]
                                         :from [:edges]
                                         :join [[:catalog_resources :sources]
                                                [:and
                                                 [:= :edges.source :sources.resource]
                                                 [:= :sources.certname_id :certnames.id]]

                                                [:catalog_resources :targets]
                                                [:and
                                                 [:= :edges.target :targets.resource]
                                                 [:= :targets.certname_id :certnames.id]]]
                                         :where [:= :edges.certname :c.certname]}
                                        :t]]}
                               :edge_data]]}
               :join-deps #{:c :certnames}}}

     :selection {:from [[:catalogs :c]]
                 :left-join [[:environments :e]
                             [:= :c.environment_id :e.id]

                             :certnames
                             [:= :c.certname :certnames.certname]

                             :producers
                             [:= :producers.id :c.producer_id]]}

     :relationships (merge certname-relations
                           {"environments" {:local-columns ["environment"]
                                            :foreign-columns ["name"]}
                            "producers" {:local-columns ["producer"]
                                         :foreign-columns ["name"]}})

     :alias "catalogs"
     :entity :catalogs
     :subquery? false
     :source-tables #{:catalogs}}))

(def catalog-input-contents-query
  "Query for the top level catalog-input-contents entity"
  (map->Query
    {::which-query :catalog-input-contents
     :can-drop-unused-joins? true
     :projections
     {"certname" {:type :string
                  :queryable? true
                  :field :certnames.certname
                  :join-deps #{:certnames}}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :certnames.catalog_inputs_timestamp
                            :join-deps #{:certnames}}
      "catalog_uuid" {:type :string
                      :queryable? true
                      :field (hsql-uuid-as-str :certnames.catalog_inputs_uuid)
                      :join-deps #{:certnames}}
      "name" {:type :string
              :queryable? true
              :field :catalog_inputs.name
              :join-deps #{:catalog_inputs}}
      "type" {:type :string
              :queryable? true
              :field :catalog_inputs.type
              :join-deps #{:catalog_inputs}}}
     :selection {:from [:catalog_inputs]
                 :left-join [:certnames
                             [:= :certnames.id :catalog_inputs.certname_id]]}
     :relationships certname-relations
     :entity :catalog-input-contents
     :alias "catalog_input_contents"
     :subquery? false
     :source-tables #{"catalog_inputs"}}))

(def catalog-inputs-query
  "Query for the catalog-inputs entity"
  (map->Query
   {::which-query :catalog-inputs
    :can-drop-unused-joins? true
    :projections
    {"certname" {:type :string
                 :queryable? true
                 :field :certnames.certname
                 :join-deps #{:certnames}}
     "producer_timestamp" {:type :timestamp
                           :queryable? true
                           :field :certnames.catalog_inputs_timestamp
                           :join-deps #{:certnames}}
     "catalog_uuid" {:type :string
                     :queryable? true
                     :field (hsql-uuid-as-str :certnames.catalog_inputs_uuid)
                     :join-deps #{:certnames}}
     "inputs" {:type :array
               :queryable? true
               :field :ci.inputs
               :join-deps #{:ci}}}
    :selection {:from [:certnames]
                :join [[{:select [:certname_id
                                  [[:array_agg [:array [:catalog_inputs.type :name]]] :inputs]]
                         :from [:catalog_inputs]
                         :group-by [:certname_id]} :ci]
                       [:= :certnames.id :ci.certname_id]]
                :where [:<> :certnames.catalog_inputs_timestamp nil]}
    :relationships certname-relations

    :entity :catalog-inputs
    :alias "catalog_inputs"
    :subquery? false
    :source-tables #{"certnames"}}))

(def edges-query
  "Query for catalog edges"
  ;; Safe or unsafe for drop-joins? (PDB-4588)
  (map->Query {::which-query :edges-query
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :edges.certname}
                             "relationship" {:type :string
                                            :queryable? true
                                            :field :edges.type}
                             "source_title" {:type :string
                                             :queryable? true
                                             :field :sources.title}
                             "source_type" {:type :string
                                            :queryable? true
                                            :field :sources.type}
                             "target_title" {:type :string
                                             :queryable? true
                                             :field :targets.title}
                             "target_type" {:type :string
                                            :queryable? true
                                            :field :targets.type}}
               :selection {:from [:edges]
                           :join [:certnames
                                  [:= :certnames.certname :edges.certname]

                                  [:catalog_resources :sources]
                                  [:and
                                   [:= :edges.source :sources.resource]
                                   [:= :certnames.id :sources.certname_id]]

                                  [:catalog_resources :targets]
                                  [:and
                                   [:= :edges.target :targets.resource]
                                   [:= :certnames.id :targets.certname_id]]]}

               :relationships certname-relations

               :alias "edges"
               :subquery? false
               :source-tables #{:edges}}))

(def resources-query
  "Query for the top level resource entity"
  ;; Safe or unsafe for drop-joins? (PDB-4588)
  (map->Query {::which-query :resources-query
               :projections {"certname" {:type  :string
                                         :queryable? true
                                         :field :c.certname}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :e.environment}
                             "resource" {:type :string
                                         :queryable? true
                                         :field (hsql-hash-as-str :resources.resource)}
                             "type" {:type :string
                                     :queryable? true
                                     :field :type}
                             "title" {:type :string
                                      :queryable? true
                                      :field :title}
                             "tag"   {:type :string
                                      :queryable? true
                                      :unprojectable? true}
                             "tags" {:type :array
                                     :queryable? true
                                     :field :tags}
                             "exported" {:type :string
                                         :queryable? true
                                         :field :exported}
                             "file" {:type :string
                                     :queryable? true
                                     :field :file}
                             "line" {:type :numeric
                                     :queryable? true
                                     :field :line}
                             "parameters" {:type :queryable-json
                                           :projectable-json? true
                                           :queryable? true
                                           :field :rpc.parameters
                                           :field-type :keyword}}

               :selection {:from [[:catalog_resources :resources]]
                           :join [:certnames
                                  [:= :resources.certname_id :certnames.id]

                                  [:catalogs :c]
                                  [:= :c.certname :certnames.certname]]
                           :left-join [[:environments :e]
                                       [:= :c.environment_id :e.id]

                                       [:resource_params_cache :rpc]
                                       [:= :rpc.resource :resources.resource]]}

               :relationships {;; Parents - direct
                               "catalogs" {:columns ["certname"]}

                               ;; Parents - transitive
                               "nodes" {:columns ["certname"]}
                               "environments" {:local-columns ["environment"]
                                               :foreign-columns ["name"]}}

               :alias "resources"
               :subquery? false
               :dotted-fields ["parameters\\..*"]
               :source-tables #{:catalog_resources}}))

(def report-events-query
  "Query for the top level reports entity"
  ;; Safe or unsafe for drop-joins? (PDB-4588)
  (map->Query {::which-query :report-events
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :reports.certname}
                             "configuration_version" {:type :string
                                                      :queryable? true
                                                      :field :reports.configuration_version}
                             "run_start_time" {:type :timestamp
                                               :queryable? true
                                               :field :reports.start_time}
                             "run_end_time" {:type :timestamp
                                             :queryable? true
                                             :field :reports.end_time}
                             "report_receive_time" {:type :timestamp
                                                    :queryable? true
                                                    :field :reports.receive_time}
                             "report" {:type :string
                                       :queryable? true
                                       :field (hsql-hash-as-str :reports.hash)}
                             "status" {:type :string
                                       :queryable? true
                                       :field :status}
                             "corrective_change" {:type :boolean
                                                  :queryable? true
                                                  :field :events.corrective_change}
                             "timestamp" {:type :timestamp
                                          :queryable? true
                                          :field :timestamp}
                             "resource_type" {:type :string
                                              :queryable? true
                                              :field :resource_type}
                             "resource_title" {:type :string
                                               :queryable? true
                                               :field :resource_title}
                             "property" {:type :string
                                         :queryable? true
                                         :field :property}
                             "new_value" {:type :string
                                          :queryable? true
                                          :field :new_value}
                             "old_value" {:type :string
                                          :queryable? true
                                          :field :old_value}
                             "message" {:type :string
                                        :queryable? true
                                        :field :message}
                             "file" {:type :string
                                     :queryable? true
                                     :field :file}
                             "line" {:type :numeric
                                     :queryable? true
                                     :field :line}
                             "containment_path" {:type :array
                                                 :queryable? true
                                                 :field :containment_path}
                             "containing_class" {:type :string
                                                 :queryable? true
                                                 :field :containing_class}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :environments.environment}
                             "latest_report?" {:type :boolean
                                               :queryable? true
                                               :unprojectable? true}
                             "report_id" {:type :numeric
                                          :queryable? false
                                          :unprojectable? true
                                          :field :events.report_id}
                             "name" {:type :string
                                     :queryable? true
                                     :field :name}}
               :selection {:from [[:resource_events :events]]
                           :join [:reports
                                  [:= :events.report_id :reports.id]]
                           :left-join [:environments
                                       [:= :reports.environment_id :environments.id]]}

               :relationships (merge certname-relations
                                     {"reports" {:local-columns ["report"]
                                                 :foreign-columns ["hash"]}
                                      "environments" {:local-columns ["environment"]
                                                      :foreign-columns ["name"]}})

               :alias "events"
               :subquery? false
               :entity :events
               :source-tables #{:resource_events}}))

(def not-active-nodes-query
  (map->Query {::which-query :not-active-nodes
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :not_active_nodes.certname}}
               :selection {:from [:not_active_nodes]}
               :subquery? false
               :alias "not_active_nodes"
               :source-tables #{:not_active_nodes}}))


(def inactive-nodes-query
  (map->Query {::which-query :inactive-nodes
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :inactive_nodes.certname}}
               :selection {:from [:inactive_nodes]}
               :subquery? false
               :alias "inactive_nodes"
               :source-tables #{:inactive_nodes}}))

(def latest-report-query
  "Usually used as a subquery of reports"
  ;; Can't usefully drop joins, since the single join is the only projection
  (map->Query {::which-query :latest-report
               :projections {"latest_report_hash" {:type :string
                                                   :queryable? true
                                                   :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:certnames]
                           :join [:reports
                                  [:and
                                   [:= :certnames.certname :reports.certname]
                                   [:= :certnames.latest_report_id :reports.id]]]}

               :alias "latest_report"
               :subquery? false
               :source-tables #{:certnames}}))

(def latest-report-id-query
  "Usually used as a subquery of reports"
  (map->Query {::which-query :latest-report-id
               :projections {"latest_report_id" {:type :numeric
                                                 :queryable? true
                                                 :field :certnames.latest_report_id}}
               :selection {:from [:certnames]}
               :alias "latest_report_id"
               :subquery? false
               :source-tables #{:certnames}}))

(def environments-query
  "Basic environments query, more useful when used with subqueries"
  (map->Query {::which-query :environments
               :projections {"name" {:type :string
                                     :queryable? true
                                     :field :environment}}
               :selection {:from [:environments]}

               :relationships {;; Children - direct
                               "inventory" {:local-columns ["name"]
                                            :foreign-columns ["environment"]}
                               "factsets" {:local-columns ["name"]
                                           :foreign-columns ["environment"]}
                               "catalogs" {:local-columns ["name"]
                                           :foreign-columns ["environment"]}
                               "reports" {:local-columns ["name"]
                                          :foreign-columns ["environment"]}

                               ;; Children - transitive
                               "facts" {:local-columns ["name"]
                                        :foreign-columns ["environment"]}
                               "fact_contents" {:local-columns ["name"]
                                                :foreign-columns ["environment"]}
                               "events" {:local-columns ["name"]
                                         :foreign-columns ["environment"]}
                               "resources" {:local-columns ["name"]
                                            :foreign-columns ["environment"]}}

               :alias "environments"
               :subquery? false
               :source-tables #{:environments}}))

(def producers-query
  "Basic producers query, more useful when used with subqueries"
  (map->Query {::which-query :producers
               :projections {"name" {:type :string
                                    :queryable? true
                                    :field :name}}
              :selection {:from [:producers]}

              :relationships {;; Children - direct
                              "factsets" {:local-columns ["name"]
                                          :foreign-columns ["producer"]}
                              "catalogs" {:local-columns ["name"]
                                          :foreign-columns ["producer"]}
                              "reports" {:local-columns ["name"]
                                         :foreign-columns ["producer"]}}

              :alias "producers"
              :subquery? false
              :source-tables #{:producers}}))

(def packages-query
  "Basic packages query"
  (map->Query {::which-query :packages
               :projections {"package_name" {:type :string
                                             :queryable? true
                                             :field :p.name}
                             "version" {:type :string
                                        :queryable? true
                                        :field :p.version}
                             "provider" {:type :string
                                         :queryable? true
                                         :field :p.provider}}

               :selection {:from [[:packages :p]]}
               :alias "packages"
               :subquery? false
               :source-tables #{:packages}}))

(def package-inventory-query
  "Packages and the machines they are installed on"
  (map->Query {::which-query :package-inventory
               :can-drop-unused-joins? true
               :projections {"certname" {:type :string
                                         :queryable? true
                                         :field :certnames.certname
                                         :join-deps #{:certnames :cp}}
                             "package_name" {:type :string
                                             :queryable? true
                                             :field :p.name
                                             :join-deps #{:p}}
                             "version" {:type :string
                                        :queryable? true
                                        :field :p.version
                                        :join-deps #{:p}}
                             "provider" {:type :string
                                         :queryable? true
                                         :field :p.provider
                                         :join-deps #{:p}}}

               :selection {:from [[:packages :p]]
                           :join [[:certname_packages :cp]
                                  [:= :cp.package_id :p.id]
                                  :certnames
                                  [:= :cp.certname_id :certnames.id]]}

               :relationships certname-relations

               :alias "package_inventory"
               :subquery? false
               :source-tables #{:packages}}))

(def factsets-query-base
  {::which-query :factsets
   :can-drop-unused-joins? true
   :projections
   {"timestamp" {:type :timestamp
                 :queryable? true
                 :field :timestamp
                 :join-deps #{:fs}}
    "facts" {:type :queryable-json
             :queryable? true
             :field {:select [(h/row-to-json :facts_data)]
                     :from [[{:select [[[:json_agg [:json_build_object
                                                    [:inline "name"] :t.name
                                                    [:inline "value"] :t.value]]
                                        :data]
                                       [(hsql-hash-as-href :fs.certname :factsets :facts)
                                        :href]]
                              :from [[{:select [[:key :name] :value :fs.certname]
                                       :from [[{:union-all
                                                [{:select :* :from [[[:jsonb_each :fs.stable]]]}
                                                 {:select :* :from [[[:jsonb_each :fs.volatile]]]}]}
                                               :t-union]]}
                                      :t]]}
                             :facts_data]]}
             :join-deps #{:fs}}
    "certname" {:type :string
                :queryable? true
                :field :fs.certname
                :join-deps #{:fs}}
    "hash" {:type :string
            :queryable? true
            :field (hsql-hash-as-str :fs.hash)
            :join-deps #{:fs}}
    "producer_timestamp" {:type :timestamp
                          :queryable? true
                          :field :fs.producer_timestamp
                          :join-deps #{:fs}}
    "producer" {:type :string
                :queryable? true
                :field :producers.name
                :join-deps #{:producers}}
    "environment" {:type :string
                   :queryable? true
                   :field :environments.environment
                   :join-deps #{:environments}}}

   :selection {:from [[:factsets :fs]]
               :left-join [:environments
                           [:= :fs.environment_id :environments.id]
                           :producers
                           [:= :producers.id :fs.producer_id]]}

   :relationships (merge certname-relations
                         {"environments" {:local-columns ["environment"]
                                          :foreign-columns ["name"]}
                          "producers" {:local-columns ["producer"]
                                       :foreign-columns ["name"]}})

   :alias "factsets"
   :entity :factsets
   :source-tables #{:factsets}
   :subquery? false})

(def factsets-query
  "Query for the top level facts query"
  (map->Query factsets-query-base))

(def factsets-with-packages-query
  "Query for factsets with the package_inventory reconstructed (used for sync)"
  (map->Query
    (-> factsets-query-base
        (assoc ::which-query :factsets-with-packages)
        (assoc-in [:projections "package_inventory"]
                  {:type :array
                   :queryable? false
                   :field :package_inventory.packages
                   :join-deps #{:certnames :package_inventory}})
        (update-in
          [:selection :left-join]
          conj
          :certnames
          [:= :certnames.certname :fs.certname]
          [{:select [[:certname_packages.certname_id :certname_id]
                     [[:array_agg :p.triple] :packages]]
            :from [:certname_packages]
            :left-join [[{:select [:id
                                  [[:array [:name :version :provider]] :triple]]
                         :from [:packages]} :p]
                        [:= :p.id :certname_packages.package_id]]
            :group-by [:certname_id]} :package_inventory]
          [:= :package_inventory.certname_id :certnames.id]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion from plan to SQL

(defn queryable-fields
  "Returns a list of queryable fields from a query record.

  These are fields with the setting :queryable? set to true."
  [{:keys [projections]}]
  (->> projections
       (filter (comp :queryable? val))
       keys
       ;; Maybe this could/should be set rather than sort.  Could make
       ;; some of the other code/logic simpler, unless it really does
       ;; need to be sorted...
       sort))

(defn projectable-fields
  "Returns a list of projectable fields from a query record.

   Fields marked as :unprojectable? true are unable to be projected and thus are
   excluded."
  [{:keys [projections]}]
  (->> projections
       (remove (comp :unprojectable? val))
       keys))

(defn projectable-json-fields
  "Returns a list of fields of queryable json fields that
   are marked that they support projectable json extraction."
  [{:keys [projections]}]
  (->> projections
       (filter (comp #(and (= :queryable-json (:type %))
                           (:projectable-json? %))
                     val))
       keys))

(defn compile-fnexpression
  ([expr]
   (compile-fnexpression expr true))
  ([{:keys [function column params]} alias?]
   [(if alias?
     (into [(keyword function) column] params)
     (into [function column] params))
    (get pg-fns->pdb-fns function)]))

(defn wrap-with-node-state-cte
  "Wrap a selection in a CTE representing expired or deactivated certnames"
  [selection node-purge-ttl]
  (let [timestamp (-> node-purge-ttl t/ago t/to-string)]
    (assoc selection
           :with {:inactive_nodes
                  {:select [[:certname]]
                   :from [:certnames_status]
                   ;; Because have bespoke parameter extraction,
                   ;; honeysql must not generate any parameters for
                   ;; this cte (i.e. everything must be inline).
                   :where [:or
                           [:and [:<> :deactivated nil] [:> :deactivated [:inline timestamp]]]
                           [:and [:<> :expired nil] [:> :expired [:inline timestamp]]]]}
                  ;; the postgres query planner currently blindly applies De Morgan's law if
                  ;; it can identify that we have negated these is-not null checks, such as
                  ;; in the default query for active nodes. So in addition to relying on the
                  ;; assumption that a user has many more active than inactive nodes, this is
                  ;; currently relying on the fact that the Postgres query planner cannot
                  ;; optimize over a CTE boundary.
                  :not_active_nodes {:select [:certname]
                                     :from [:certnames_status]
                                     :where [:or
                                             [:is-not :deactivated nil]
                                             [:is-not :expired nil]]}})))

(defn quote-projections
  [projection]
  (when (> (count projection) 63)
    (throw (bad-query-ex
            (tru "Projected field name ''{0}'' exceeds current maximum length of 63 characters"
                 (count projection) projection))))
  projection)

(defn include-projection-dependencies
  "Returns projections after adding any dependencies of its memebers,
  i.e. any :depends listed in the query-rec proj-info for the
  projection."
  [proj-info projections]
  ;; Traverse all the projections to add any the proj-info for missing
  ;; :depends.  proj-info is the query req field info map.
  (loop [result (vec projections)
         already-projected? (->> projections (map first) set)
         [[_fname {:keys [depends] :as _info} :as proj] & projections] projections]
    (if-not proj
      result
      (let [[new-projections result]
            ;; Traverse all the :depends and add [dep dep-info] to the
            ;; result for any that are missing.
            (loop [result result
                   already-projected? already-projected?
                   [dep & depends] depends]
              (if-not dep
                [already-projected? result]
                (if (already-projected? dep)
                  (recur result already-projected? depends)
                  (recur (let [dep-info (proj-info dep)]
                           (assert (= (:queryable? dep-info) false))
                           (conj result [dep dep-info]))
                         (conj already-projected? dep)
                         depends))))]
        ;; Fold the information from the inner loop into the result
        (recur result
               (set/union already-projected? new-projections)
               projections)))))

;; Skipped (only safe if incoming data is safe)
(defn sql-select-for-query
  "Returns the honesql representation of the query's select,
  i.e. {:select expr}."
  [{:keys [projected-fields call selection projections] :as _query}]
  ;; This is where we finally fully expand the projections.
  (let [calls (mapv :statement call)
        non-call-projections (if (and (empty? calls) (empty? projected-fields))
                               projections
                               projected-fields)
        ;; map honeysql v1 distinct modifier to honeysqlv2 :select-distinct
        select-distinct (some #{:distinct} (selection :modifiers))
        select (if select-distinct :select-distinct :select)]
    (assoc (dissoc selection :modifiers) ;; honeysql v2 rejects unrecognized keys
           select (->> non-call-projections
                       (remove (comp :unprojectable? second))
                       ;; Currently this does not support deps for
                       ;; projected functions i.e. :calls.
                       (include-projection-dependencies projections)
                       (mapv (fn [[name {:keys [field]}]]
                               [field (quote-projections name)]))
                       (into calls)))))

(defn fn-binary-expression->hsql
  "Produce a predicate that compares the result of a function against a
  provided value. The operator, function name and its arguments must
  have already been validated."
  [op function args]
  (let [args
        (case function
          ("sum" "avg" "min" "max" "count" "jsonb_typeof") (map keyword args)
          "to_char" [(-> args first keyword) [:inline (second args)]]
          (throw (bad-query-ex (tru "{0} is not a valid function"
                                                 (pr-str function)))))]
    [(keyword op)
     (apply vector (keyword function) args)
     [:raw "?"]]))

(defn munge-query-ordering
  [clauses]
  (for [c clauses]
    (if (vector? c)
      (update c 1 {:ascending :asc :descending :desc})
      [c :asc])))

(defprotocol SQLGen
  (-plan->sql [query options] "Given the `query` plan node, convert it to a SQL string"))

(extend-protocol SQLGen
  Query
  (-plan->sql [{:keys [limit offset order-by group-by projected-fields subquery? where] :as query}
               {:keys [node-purge-ttl] :as options}]
    (s/validate [projection-schema] projected-fields)
    (let [has-where? (boolean where)
          sql (cond-> query
                has-where? (update :selection #(hsql/where % (-plan->sql where options)))
                true sql-select-for-query
                true (dissoc :selection-params) ;; honeysql v2 disallows unrecognized keys
                (not subquery?) (wrap-with-node-state-cte node-purge-ttl)
                group-by (assoc :group-by group-by)
                limit (assoc :limit limit)
                offset (assoc :offset offset)
                order-by (assoc :order-by order-by)
                true (sql/format :allow-dashed-names? true)
                true first)]
      (if (:subquery? query)
        [:nest [:raw sql]]
        sql)))

  InExpression
  (-plan->sql [{:keys [column subquery]} options]
    ;; 'column' is actually a vector of columns.
    (s/validate [column-schema] column)
    ;; if a field has type jsonb, cast that field in the subquery to jsonb
    (let [fields (mapv :field column)
          outer-types (mapv :type column)
          projected-fields (:projected-fields subquery)
          inner-columns (map first projected-fields)
          inner-types (map (comp :type second) projected-fields)
          coercions (mapv convert-type inner-columns inner-types outer-types)]
      [:in (into [:composite] fields)
       ;; FIXME: hackery to get properly nested raw & function calls
       {:select (mapv (fn [v] (if (keyword? v) v [v])) coercions)
        :from [[(-plan->sql subquery options) :sub]]}]))

  JsonContainsExpression
  (-plan->sql [{:keys [column-data array-in-path]} _opts]
    ;; This distinction is necessary (at least) because @> cannot
    ;; traverse into arrays, but should be otherwise preferred
    ;; because it can use an index.
    (if array-in-path
      [:= [:#> (:field column-data) [:raw "?"]] [:raw "?"]]
      [pgop/at> (:field column-data) [:raw "?"]]))

  FnBinaryExpression
  (-plan->sql [{:keys [function args operator]} _opts]
    (fn-binary-expression->hsql operator function args))

  JsonbPathBinaryExpression
  (-plan->sql [{:keys [field value column-data operator]} _opts]
    (su/jsonb-path-binary-expression operator
                                     (if (vector? (:field column-data))
                                       (:field column-data)
                                       field)
                                     value))

  JsonbScalarRegexExpression
  (-plan->sql [{:keys [field]} _opts]
    (su/jsonb-scalar-regex field))

  BinaryExpression
  (-plan->sql [{:keys [column operator value]} options]
    (apply vector
           :or
           (map #(vector operator (-plan->sql %1 options) (-plan->sql %2 options))
                (cond
                  (map? column) [(:field column)]
                  (vector? column) (mapv :field column)
                  :else [column])
                (utils/vector-maybe value))))

  InArrayExpression
  (-plan->sql [{:keys [column value]} _opts]
    (s/validate column-schema column)
    (su/sql-in-array (:field column) value))

  ArrayBinaryExpression
  (-plan->sql [{:keys [column]} _opts]
    (s/validate column-schema column)
    (su/sql-array-query-string (:field column)))

  RegexExpression
  (-plan->sql [{:keys [column]} _opts]
    (s/validate column-schema column)
    (su/sql-regexp-match (:field column)))

  ArrayRegexExpression
  (-plan->sql [{:keys [column]} _opts]
    (s/validate column-schema column)
    (su/sql-regexp-array-match-v2 (:field column)))

  PathArrayMatch
  (-plan->sql [{:keys [column path]} _opts]
    (su/path-array-col-matches-ast-path (:field column) (:signature column) path))

  PathArrayAnyMatch
  (-plan->sql [{:keys [column paths]} _opts]
    (su/path-array-col-matches-any-ast-path (:field column) (:signature column) paths))

  PathArrayRegexMatch
  (-plan->sql [{:keys [column path-rx]} _opts]
    (s/validate column-schema column)
    (s/validate ast-path-schema path-rx)
    (su/path-array-col-matches-rx-vec (:field column) (:signature column) path-rx))

  NullExpression
  (-plan->sql [{:keys [column] :as expr} options]
    (s/validate column-schema column)
    (let [queryable-json? (= :queryable-json (:type column))
          hsql-col? (and (vector? (:field column))
                         (not (= :raw (-> column :field first))))
          lhs (if queryable-json?
                (if hsql-col?
                  (:field column)
                  (name (h/extract-sql (:field column))))
                (-plan->sql (:field column) options))
          json? (or queryable-json? (= :jsonb-scalar (:type column)))]
      (if json?
        (if hsql-col?
          [(if (:null? expr) := :<>) [:jsonb_typeof lhs] [:inline "null"]]
          (su/jsonb-null? lhs (boolean (:null? expr))))
        [(if (:null? expr) :is :is-not) lhs nil])))

  AndExpression
  (-plan->sql [expr options]
    (apply vector :and (map #(-plan->sql % options) (:clauses expr))))

  OrExpression
  (-plan->sql [expr options]
    (apply vector :or (map #(-plan->sql % options) (:clauses expr))))

  NotExpression
  (-plan->sql [expr options]
    [:not (-plan->sql (:clause expr) options)])

  Object
  (-plan->sql [obj _opts]
    obj))

(defn plan->sql
  "Convert `query` to a SQL string"
  [query options]
  (-plan->sql query options))

(defn binary-expression?
  "True if the plan node is a binary expression"
  [node]
  ;; Currently, this really just means "should we arrange for a single
  ;; ? parameter corresponding with the :value?".  cf. extract-params.
  (or (instance? BinaryExpression node)
      (instance? RegexExpression node)
      (instance? InArrayExpression node)
      (instance? ArrayBinaryExpression node)
      (instance? ArrayRegexExpression node)))

(defn parse-dot-query
  "Transforms a dotted query into a JSON structure appropriate
   for comparison in the database."
  [{:keys [field value] :as node} state]
  ;; node is JsonContainsExpression
  (let [[column & path] (parse/parse-field field 0 {:indexes? false
                                                    :matches? false})
        ;; FIXME: this is a conflation - one fix might be to parse all
        ;; fields *once* at the start of query processing, and then
        ;; pass that representation all the way through.
        ;;
        ;; The handling of match() wrt fact_paths complicates that
        ;; because right now we don't have any (efficient) way of
        ;; knowing all the types of the path elements we retrieve from
        ;; the path_array, i.e. for ["foo" "5" "bar"], is "5" a custom
        ;; fact name, or an array lookup?  The situation could be
        ;; different if we pushed the path lookups further down, and
        ;; didn't insinuate them via AST.  At the moment, we preserve
        ;; backward compatibility by just treating everything as a
        ;; named field by setting indexes? and matches? above.
        maybe-index? #(re-matches #"[0-9]+" (:name %))]
    (if (some maybe-index? path)
      ;; If this matches, then the path component might be an array
      ;; access, e.g. via the database-generated paths created by
      ;; maybe-add-match-function-filter like foo.5.bar, and so we
      ;; have to be conservative and set array-in-path so that we'll
      ;; eventually use the right json operators (e.g. #> instead of
      ;; @>).
      {:node (assoc node
                    :value ["?" "?"]
                    :field (:name column)
                    :array-in-path true)
       :state (reduce conj state [(jdbc/strs->db-array (map :name path))
                                  (su/munge-jsonb-for-storage value)])}
      {:node (assoc node
                    :value "?"
                    :field (:name column)
                    :array-in-path false)
       :state (conj state
                    (su/munge-jsonb-for-storage
                     ;; Convert path [a b c] and value d to {a {b {c d}}}
                     (reduce (fn [result name] (hash-map name result))
                             (cons value (reverse (map :name path))))))})))

(defn parse-dot-query-with-array-elements
  "Transforms a dotted query into a JSON structure appropriate
   for comparison in the database."
  [{:keys [field value] :as node} state]
  ;; node is JsonbPathBinaryExpression
  ;; Convert facts.foo[5] to ["facts" "foo" 5]
  (let [[column & path] (mapcat #(case (:kind %)
                                   ::parse/indexed-field-part [(:name %) (:index %)]
                                   ::parse/named-field-part [(:name %)])
                                (parse/parse-field field))
        parameters (concat path [(su/munge-jsonb-for-storage value)
                                 (first path)])]
    {:node (assoc node
                  :value (repeat (count path) "?")
                  :field column)
     :state (reduce conj state parameters)}))


;; Note to future query engine hackers: the parameter extraction mechanism
;; relies on the order in which the query is traversed here being *exactly* the
;; same as the order honeysql traverses its tree. This is tricky, and hard to
;; keep working correctly. A much better mechanism would be to rely on honeysql
;; to do parameter extraction itself; i.e. instead of replacing things with
;; question marks here, just pass strings to honeysql. But getting there is a
;; very large change.
(defn extract-params
  "Extracts the node's expression value, puts it in state
   replacing it with `?`, used in a prepared statement"
  [node state]
  (cond
    (binary-expression? node)
    {:node (assoc node :value "?")
     :state (conj state (:value node))}

    (instance? JsonContainsExpression node)
    (parse-dot-query node state)

    (instance? JsonbPathBinaryExpression node)
    ;; Q: this shouldn't accept match()es?
    (parse-dot-query-with-array-elements node state)

    (instance? FnBinaryExpression node)
    {:node (assoc node :value "?")
     :state (conj state (:value node))}

    (instance? JsonbScalarRegexExpression node)
    {:node (assoc node :value "?")
     :state (conj state (:value node))}

    (instance? FnExpression node)
    {:node (assoc node :value "?")
     :state (apply conj (:params node) state)}

    ;; Handle a parameterized :selection -- when the :selection
    ;; appears in the tree traversal put the corresponding parameter values
    ;; from :selection-params.
    ;; See rewrite-fact-query for an example.
    (and (map? node)
         (:selection-params node))
    {:node nil
     :state (apply conj (:selection-params node) state)}))

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

(def user-name->query-rec-name
  {"select_catalogs" catalog-query
   "select_catalog_input_contents" catalog-input-contents-query
   "select_catalog_inputs" catalog-inputs-query
   "select_edges" edges-query
   "select_environments" environments-query
   "select_producers" producers-query
   "select_packages" packages-query
   "select_package_inventory" package-inventory-query
   "select_events" report-events-query
   "select_facts" facts-query
   "select_factsets" factsets-query
   "select_fact_contents" fact-contents-query
   "select_fact_paths" fact-paths-query
   "select_nodes" nodes-query
   "select_not_active_nodes" not-active-nodes-query
   "select_inactive_nodes" inactive-nodes-query
   "select_latest_report" latest-report-query
   "select_latest_report_id" latest-report-id-query
   "select_params" resource-params-query
   "select_reports" reports-query
   "select_inventory" inventory-query
   "select_resources" resources-query})

(defn user-query->logical-obj
  "Keypairs of the stringified subquery keyword (found in user defined queries) to the
   appropriate plan node"
  [subquery]
  (-> (get user-name->query-rec-name subquery)
      (assoc :subquery? true)))

(defn strip-function-calls
  [column-or-columns]
  (let [{functions true
         nonfunctions false} (->> column-or-columns
                                  utils/vector-maybe
                                  (group-by (comp #(= "function" %) first)))]
    [(vec (map rest functions))
     (vec nonfunctions)]))

(defn no-type-restriction?
  "Determine whether an expression already contains a restriction of values to
   numbers."
  [expr]
  (let [r ["=" ["function" "jsonb_typeof" "value"] "number"]]
    (->> expr
         (tree-seq vector? identity)
         (not-any? #(= % r)))))

(defn numeric-fact-functions?
  "Determine whether an extract clause contains numeric aggregate functions,
   implying that the query must be restricted for successful type coercion."
  [clauses]
  (->> clauses
       strip-function-calls
       first
       (map first)
       (some numeric-functions)))

(defn- db-path->ast-field [path]
  (try
    (parse/path-names->field-str path)
    (catch ExceptionInfo ex
      (when-not (= ::parse/unquotable-field-segment (:kind (ex-data ex)))
        (throw ex))
      (throw (Exception.
              (tru "Currently unable to match this path: {0}"
                   (pr-str (-> ex ex-data :data))))))))

(defn maybe-add-match-function-filter [operator field value]
  ;; FIXME: this currently treats *all* path segments as match()es
  ;; when there's any match().  Perhaps change in the next X release,
  ;; or next endpoint version.
  (let [parts (parse/parse-field field)]
    (if-not (some #(= ::parse/match-field-part (:kind %)) parts)
      ;; dotted path without any match() parts, if querying trusted
      ;; prepend "facts." so that it hits the merged jsonb index instead
      ;; of needing to scan the table.
      (if (= "trusted" (:name (first parts)))
        [operator (str "facts." field) value]
        [operator field value])
      (let [[head & path] parts
            path (if (= (:name head) "trusted")
                   ;; Real location is facts.trusted...
                   (cons head path)
                   path)
            ;; Q: factpath-regexp-to-regexp?
            pg-rx (->> path
                       (mapcat #(case (:kind %)
                                  ::parse/indexed-field-part [(:name %) (:index %)]
                                  ::parse/match-field-part [(:pattern %)]
                                  ::parse/named-field-part [(:name %)]))
                       (str/join "#~"))
            ;; Right now, this code communicates the paths via AST
            ;; dotted fields, which is insufficient because the
            ;; current syntax can't represent all possible fact names
            ;; (see db-path->ast-field).
            ;;
            ;; Eventually, and depending on what we decide about
            ;; match(), we might want to either:
            ;;   - move this down into the ->plan functions so we
            ;;     don't have to round-trip through AST, or otherwise
            ;;     allow us to carry the parsed field representation
            ;;     rather than a reconsituted AST field string.
            ;;   - rework AST's quoting syntax (in a new endpoint
            ;;     version)
            ;;   - just rely on the well specified syntax we already
            ;;     have, and provide support paths as JSON vectors,
            ;;     e.g. ["foo" "bar"], ["foo" ["nth" "bar" 5], "baz"],
            ;;     ["foo" ["match" "\\d+"]], etc.
            fact_paths (->> (jdbc/query-to-vec (str "SELECT path_array FROM fact_paths"
                                                    "  WHERE (path ~ ? AND path IS NOT NULL)")
                                               (doto (PGobject.)
                                                 (.setType "text")
                                                 (.setValue pg-rx)))
                            (map :path_array)
                            (map (fn [path]
                                   (if (= (first path) "trusted")
                                     path
                                     (cons "facts" path))))
                            ;; cf. parse-dot-query which relies on the
                            ;; fact that any array indexing will match
                            ;; \d+, i.e. foo.5.bar.
                            (map db-path->ast-field))]
        (case (count fact_paths)
          0 ["or" "false"] ;; Could this just be false?
          1 (vector operator (first fact_paths) value)
          (into ["or"] (map #(vector operator % value) fact_paths)))))))

(defn expand-query-node
  "Expands/normalizes the user provided query to a minimal subset of the
  query language"
  [node]
  (cm/match [node]

            [[(op :guard #{"=" ">" "<" "<=" ">=" "~"})
              ;; Q: why isn't "parameters" included in the guard set
              ;; (cf. :dotted-fields below)?
              (column :guard #(and (string? %)
                                   (#{"facts" "trusted"} (-> (parse/parse-field %)
                                                             first
                                                             :name))))
              value]]
            ;; (= :inventory (get-in (meta node) [:query-context :entity]))
            (maybe-add-match-function-filter op column value)

            [["extract" (columns :guard numeric-fact-functions?) (expr :guard no-type-restriction?)]]
            (when (= :facts (get-in meta node [:query-context :entity]))
              ["extract" columns ["and" ["=" ["function" "jsonb_typeof" "value"] "number"] expr]])

            [[(op :guard #{"=" "<" ">" "<=" ">="}) "value" (value :guard #(number? %))]]
            ["and" ["=" ["function" "jsonb_typeof" "value"] "number"] [op "value" value]]

            [["=" "value" (value :guard #(string? %))]]
            ["and" ["=" ["function" "jsonb_typeof" "value"] "string"] ["=" "value" value]]

            [["=" "value" (value :guard #(ks/boolean? %))]]
            ["and" ["=" ["function" "jsonb_typeof" "value"] "boolean"] ["=" "value" value]]

            [["=" "type" "any"]]
            ::elide

            [["=" ["node" "active"] value]]
            (expand-query-node ["=" "node_state" (if value "active" "inactive")])

            [["or" ["=" ["node" "active"] true]
              ["=" ["node" "active"] false]]]
            (expand-query-node ["=" "node_state" "any"])

            [["or" ["=" ["node" "active"] false]
                   ["=" ["node" "active"] true]]]
            (expand-query-node ["=" "node_state" "any"])

            [["=" "node_state" value]]
            (case (str/lower-case (str value))
              "active" ["not" ["in" "certname"
                               ["extract" "certname"
                                ["select_not_active_nodes"]]]]
              "inactive" ["in" "certname"
                          ["extract" "certname"
                           ["select_inactive_nodes"]]]
              "any" ::elide)

            [[(op :guard #{"=" "~"}) ["parameter" param-name] param-value]]
            ["in" "resource"
             ["extract" "res_param_resource"
              ["select_params"
               ["and"
                [op "res_param_name" param-name]
                [op "res_param_value" (su/db-serialize param-value)]]]]]

            [[(op :guard #{"=" "~"}) ["fact" fact-name]
              (fact-value :guard #(or (string? %) (instance? Boolean %)))]]
              ["in" "certname"
               ["extract" "certname"
                ["select_facts"
                 ["and"
                  ["=" "name" fact-name]
                  [op "value" fact-value]]]]]

              [["in" ["fact" fact-name] ["array" fact-values]]]
              (if-not (= (count (set (map type fact-values))) 1)
                (throw (bad-query-ex
                         (tru "All values in 'array' must be the same type.")))
                ["in" "certname"
                 ["extract" "certname"
                  ["select_facts"
                   ["and"
                    ["=" "name" fact-name]
                    ["in" "value" ["array" fact-values]]]]]])

              [[(op :guard #{"=" ">" "<" "<=" ">="}) ["fact" fact-name] fact-value]]
              (if-not (number? fact-value)
                (throw (bad-query-ex
                         (tru "Operator ''{0}'' not allowed on value ''{1}''" op fact-value)))
                ["in" "certname"
                 ["extract" "certname"
                  ["select_facts"
                   ["and"
                    ["=" "name" fact-name]
                    [op "value" fact-value]]]]])

            [["subquery" sub-entity expr]]
            (let [relationships (get-in (meta node) [:query-context :relationships sub-entity])]
              (if relationships
                (let [{:keys [columns local-columns foreign-columns]} relationships]
                  (when-not (or columns (and local-columns foreign-columns))
                    (throw (bad-query-ex
                            (tru "Column definition for entity relationship ''{0}'' not valid"
                                 sub-entity))))
                  ["in" (or local-columns columns)
                   ["extract" (or foreign-columns columns)
                    [(str "select_" sub-entity) expr]]])
                (throw (bad-query-ex (tru "No implicit relationship for entity ''{0}''"
                                          sub-entity)))))

            [["=" "latest_report?" value]]
            (let [entity (get-in (meta node) [:query-context :entity])
                  latest (case entity
                           :reports
                           ["in" "hash"
                            ["extract" "latest_report_hash"
                             ["select_latest_report"]]]

                           :events
                           ["in" "report_id"
                            ["extract" "latest_report_id"
                             ["select_latest_report_id"]]]
                           (throw
                            (bad-query-ex
                             (tru "Field 'latest_report?' not supported on endpoint ''{0}''"
                                  entity))))]
              (if value latest ["not" latest]))

            [[op (field :guard #{"new_value" "old_value"}) value]]
            [op field (su/db-serialize value)]

            [["=" field nil]]
            ["null?" (formatter/dashes->underscores field) true]

            [[op "tag" array-value]]
            [op "tags" (str/lower-case array-value)]

            :else nil))

(def simple-binary-operators
  #{"=" ">" "<" ">=" "<=" "~" "~>"})

(def simple-binary-operator-checker
  "A function that will return nil if the query snippet successfully validates, otherwise
  will return a data structure with error information"
  (s/checker [(s/one
               (apply s/enum simple-binary-operators)
               :operator)
              (s/one (s/cond-pre s/Str
                                 [(s/one s/Str :nested-field)
                                  (s/one s/Str :nested-value)])
                     :field)
              (s/one (s/cond-pre [(s/cond-pre s/Str s/Int)]
                                 s/Str s/Bool s/Int pls/Timestamp Double)
                     :value)]))

(defn vec?
  "Same as set?/list?/map? but for vectors"
  [x]
  (instance? clojure.lang.IPersistentVector x))

(defn removeable-clause?
  [clause]
  (or (= ::elide clause) (= ["not"] clause) (= ["and"] clause) (= ["or"] clause)))

(defn remove-elided-nodes
  "This step removes elided nodes (marked with ::elide by expand-user-query) from
  `and` and `or` clauses."
  [node]
  (cm/match [node]
            [[& clauses]]
            (into [] (remove removeable-clause? clauses))

            :else nil))

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (let [query-context (:query-context (meta node))]
    (cm/match [node]

              [["=" field value]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when (and (= :numeric col-type) (string? value))
                  (throw
                   (bad-query-ex
                    (tru
                     "Argument \"{0}\" is incompatible with numeric field \"{1}\"."
                     value (name field))))))

              [[(:or ">" ">=" "<" "<=")
                (field :guard #(= (count (parse/parse-field %)) 1))
                _]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when-not (or (vec? field)
                              (contains? #{:numeric :timestamp :jsonb-scalar}
                                         col-type))
                  (throw
                   (bad-query-ex
                    (tru "Query operators >,>=,<,<= are not allowed on field {0}" field)))))

              [["~>" field _]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when-not (contains? #{:encoded-path :path-array} col-type)
                  (throw
                   (bad-query-ex (tru "Query operator ~> is not allowed on field {0}" field)))))

              ;;This validation check is added to fix a failing facts
              ;;test. The facts test is checking that you can't submit
              ;;an empty query, but a ["=" ["node" "active"] true]
              ;;clause is automatically added, causing the empty query
              ;;to fall through to the and clause. Adding this here to
              ;;pass the test, but better validation for all clauses
              ;;needs to be added
              [["and" & clauses]]
              (if (empty? clauses)
                (throw
                 (bad-query-ex
                  (tru "''and'' takes at least one argument, but none were supplied")))
                (when (some (fn [clause] (and (not= ::elide clause) (empty? clause))) clauses)
                  (throw
                   (bad-query-ex
                    (tru "[] is not well-formed: queries must contain at least one operator")))))

              [["or" & clauses]]
              (when (empty? clauses)
                (throw
                 (bad-query-ex
                  (tru "''or'' takes at least one argument, but none were supplied"))))

              ;;Facts is doing validation against nots only having 1
              ;;clause, adding this here to fix that test, need to make
              ;;another pass once other validations are known
              [["not" & clauses]]
              (when (not= 1 (count clauses))
                (throw
                 (bad-query-ex (tru "''not'' takes exactly one argument, but {0} were supplied"
                                    (count clauses)))))

              [[op & clauses]]
              (when (simple-binary-operators op)
                (when (not= 2 (count clauses))
                  (throw (bad-query-ex (tru "{0} requires exactly two arguments" op))))
                (when (simple-binary-operator-checker node)
                  (throw (bad-query-ex (tru "Invalid {0} clause" op)))))

              :else nil)))

(defn expand-user-query
  "Expands/translates the query from a user provided one to a
  normalized query that only contains our lower-level operators.
  Things like [node active] will be expanded into a full
  subquery (via the `in` and `extract` operators)"
  [user-query]
  (:node (zip/post-order-transform (zip/tree-zipper user-query)
                                   [expand-query-node validate-binary-operators remove-elided-nodes])))

(declare user-node->plan-node)

(defn subquery-expression?
  "Returns true if expr is a subquery expression"
  [expr]
  (contains? (ks/keyset user-name->query-rec-name)
             (first expr)))

(defn create-json-path-extraction
  "Given a base json field and a path of keys to traverse, construct the proper
  SQL query of the form base->'key'->'key2'..."
  [field path]
  (when (string? field) ; internal expectation
    (throw (ex-info "invalid field type" {:value field})))
  ;; The str is to make sure 5 comes through as '5' -- see comments below.
  (apply vector :-> field (map #(vector :inline (str %)) path)))

(defn create-json-subtree-projection
  [[top & path :as _parsed-field] projections]
  {:type :json
   :queryable? false
   :field (create-json-path-extraction
           ;; Extract the original field as a string
           (let [{:keys [field field-type]} (-> top :name projections)]
             (case field-type
               (:keyword :raw) field
               (do
                 (when-not (vector? field)
                   (throw
                    (bad-query-ex (tru "Cannot determine field type for field ''{0}''" field))))
                 field)))
           (map #(case (:kind %)
                   ::parse/named-field-part (:name %))
                path))
   ;; json-path will be something like ("facts" "kernel" ...)
   :join-deps (get-in projections [(:name top) :join-deps])})

(defn create-extract-node*
  "Returns a `query-rec` that has the correct projection for the given
   `column-list`. Updating :projected-fields causes the select in the SQL query
   to be modified."
  [{:keys [projections] :as query-rec} column-list expr]
  (let [names->fields (fn [projections]
                        (mapv (fn [field parsed]
                                (vector field
                                        (if (> (count parsed) 1) ;; dotted projection?
                                          (create-json-subtree-projection parsed projections)
                                          (projections field))))
                              column-list
                              (map parse/parse-field column-list)))]
    (if (or (nil? expr)
            (not (subquery-expression? expr)))
      (assoc query-rec
             :where (user-node->plan-node query-rec expr)
             :projected-fields (names->fields projections))
      (let [[subname & subexpr] expr
            logobj (user-query->logical-obj subname)
            projections (:projections logobj)]
        (assoc logobj
               :projected-fields (names->fields projections)
               :where (some->> (seq subexpr)
                               first
                               (user-node->plan-node logobj)))))))

(defn extract-expression?
  "Returns true if expr is an extract expression"
  [expr]
  (let [f (first expr)]
    (and (str f) (= f "extract"))))

(defn get-clause
  [clause clauses]
  (when-let [candidates (seq (filter #(= (first %) clause) clauses))]
    (when (> (count candidates) 1)
      (throw (bad-query-ex (tru "Multiple ''{0}'' clauses are not permitted" clause))))
    (when (not (second (first candidates)))
      (throw (bad-query-ex (tru "Received ''{0}'' clause without an argument" clause))))
    (-> candidates first second)))

(defn process-order-by
  [clauses]
  (when clauses
    (for [clause clauses]
      (if (vector? clause) (mapv keyword clause) (keyword clause)))))

(defn create-paging-map
  "Given a list of clauses [['limit'1 1] ['offset' 1], etc], convert to a map
   {:limit 1 :offset 1}"
  [clauses]
  {:limit (get-clause "limit" clauses)
   :offset (get-clause "offset" clauses)
   :order-by (process-order-by (get-clause "order_by" clauses))})

(defn update-selection
  [query-rec offset limit order-by]
  (cond-> query-rec
    offset (assoc-in [:selection :offset] [:inline offset])
    limit (assoc-in [:selection :limit] [:inline limit])
    order-by (assoc-in [:selection :order-by] order-by)))

(pls/defn-validated create-from-node
  :- {(s/optional-key :projected-fields) [projection-schema]
      s/Any s/Any}
  "Create an explicit subquery declaration to mimic the select_<entity>
   syntax."
  [entity expr clauses]
  (let [query-rec (user-query->logical-obj (str "select_" (formatter/dashes->underscores entity)))
        {:keys [limit offset order-by]} (create-paging-map clauses)]
    (if (extract-expression? expr)
      (let [[_extract columns remaining-expr] expr
            column-list (utils/vector-maybe columns)
            projections (:projections query-rec)]
        (-> query-rec
            (assoc :projected-fields (mapv (fn [name] [name (projections name)])
                                           column-list)
                   :where (user-node->plan-node query-rec remaining-expr))
            (update-selection offset limit order-by)))
      (-> query-rec
          (assoc :where (user-node->plan-node query-rec expr))
          (update-selection offset limit order-by)))))

(defn create-fnexpression
  [[f & args]]
  (let [[column join-deps params]
        (if (seq args)
          [(:field (first args)) (:join-deps (first args)) (rest args)]
          ["*" #{} []])
        qmarks (repeat (count params) "?")
        fnmap {:function (pdb-fns->pg-fns f)
               :column column
               :join-deps join-deps
               :params (vec params)
               :args (vec qmarks)}
        compiled-fn (compile-fnexpression fnmap)]
    (map->FnExpression (-> fnmap
                           (assoc :statement compiled-fn)))))

(defn group-by-entry->sql-field
  "Converts a column into its qualified SQL field name or a function
  expression into its function name. Throws a bad-query-ex if there is
  an invalid field or function"
  [query-rec column-or-fn-name]
  ;; Just split on dot for now (as a hack) - we'll use the strict
  ;; parser once it's available (after 6.18.0 and 7.5.0)."
  (let [parsed (parse/parse-field column-or-fn-name)]
    (if (and (> (count parsed) 1)
             (get-in query-rec [:projections
                                (:name (first parsed))
                                :field]))
      ;; Turn facts.foo into a double quoted keyword so that the SQL identifier `:"facts.foo"`
      ;; matches the extraction of (fs.volatile||fs.stable) AS "facts.foo" from the selection
      (keyword (jdbc/double-quote column-or-fn-name))
      (or (get-in query-rec [:projections column-or-fn-name :field])
          (if (some #{column-or-fn-name} (keys pdb-fns->pg-fns))
            (keyword column-or-fn-name)
            (throw (bad-query-ex
                    (tru "{0} is niether a valid column name nor function name"
                         (pr-str column-or-fn-name)))))))))

(defn group-by-entries->fields
  "Convert a list of group by columns and functions to their true SQL field names."
  [query-rec entries]
  (->> entries
       (map #(if (= "function" (first %)) (second %) %))
       (sort-by #(if (string? %) % (:column %)))
       (mapv (partial group-by-entry->sql-field query-rec))))

(pls/defn-validated create-extract-node
  :- {(s/optional-key :projected-fields) [projection-schema]
      s/Any s/Any}
  [query-rec column expr]
  (let [[fcols cols] (strip-function-calls column)
        coalesce-fact-values (fn [col]
                               (if (and (:factsets (:source-tables query-rec))
                                        (= "value" col))
                                 {:field (convert-type "value" :jsonb-scalar :numeric)
                                  :join-deps #{:factsets}}
                                 (if-let [column (get-in query-rec [:projections col])]
                                   (select-keys column [:field :join-deps])
                                   col)))]
    (if-let [calls (seq
                     (map (fn [[name & args]]
                            (apply vector
                                   name
                                   (if (empty? args)
                                     [{:field :* :join-deps #{}}]
                                     (map coalesce-fact-values args))))
                          fcols))]
      (-> query-rec
          (assoc :call (map create-fnexpression calls))
          (create-extract-node* cols expr))
      (create-extract-node* query-rec cols expr))))

(defn try-parse-timestamp
  "Try to convert a string to a timestamp, throwing an exception if it fails"
  [ts]
  (or (t/to-timestamp ts)
      (throw (bad-query-ex (tru "''{0}'' is not a valid timestamp value" ts)))))

(defn validate-argument-count
  [f args allowed-count]
  (let [arg-count (count args)]
    (when-not (allowed-count arg-count)
      (throw (bad-query-ex
              (tru "wrong number of arguments ({0}) provided for function {1}"
                   arg-count f))))))

;; TODO remove me when no longer used
(defn validate-queryable-field
  [query-rec maybe-field]
  (when-not (some #(= % maybe-field) (queryable-fields query-rec))
    (throw (bad-query-ex (tru "field {0} is not a valid queryable field"
                                           (pr-str maybe-field))))))

;; TODO we should reuse validate-query-operation-fields (defined below) here. Don't allow
;; any json-extracts, functions do not support them currently
(defn validate-fn-binary-expr
  "Throws an exception if the args are not appropriate for the AST
  function named f."
  [query-rec f args]
  (let [maybe-field (first args)]
    (case f
      ("sum" "avg" "min" "max")
      (do
        (validate-argument-count f args #{1})
        (validate-queryable-field query-rec maybe-field)
        ;; TODO Is this numeric guard too strict?  AST docs suggest maybe not.
        ;; TODO add a test, no tests failed when 'numeric' was misspelled
        (when (not= :numeric (get-in query-rec [:projections maybe-field :type]))
          (throw (bad-query-ex (tru "field {0} must be a numeric type"
                                                 (pr-str maybe-field))))))

      "count"
      (do
        (validate-argument-count f args #{0 1})
        (when maybe-field
          (validate-queryable-field query-rec maybe-field)))

      "to_string"
      (do
        (validate-argument-count f args #{2})
        ;; TODO: validate second to_char field (the string format) for
        ;; now we are relying on the quoting in
        ;; fn-binary-expression->hsql to maintain the safety of the
        ;; format string
        (validate-queryable-field query-rec maybe-field))

      "jsonb_typeof"
      (do
        (validate-argument-count f args #{1})
        (validate-queryable-field query-rec maybe-field))

      (throw (bad-query-ex (tru "{0} is not a valid function"
                                             (pr-str f)))))))

(defn user-node->plan-node
  "Create a query plan for `node` in the context of the given query (as `query-rec`)"
  [query-rec node]
  (cm/match [node]
            ;; operator-function-clause
            [[(op :guard #{"=" "~" ">" "<" "<=" ">="}) ["function" f & args] value]]
            (do
              (validate-fn-binary-expr query-rec f args)
              (map->FnBinaryExpression {:operator op
                                        :function f
                                        :args args
                                        :value value}))

            [["=" column-name value]]
            ;; FIXME: pass parsed representation down
            (let [[top & path] (parse/parse-field column-name)
                  cinfo (get-in query-rec [:projections (:name top)])]
              (case (:type cinfo)
               :timestamp
               (map->BinaryExpression {:operator :=
                                       :column cinfo
                                       :value (try-parse-timestamp value)})

               :array
               (map->ArrayBinaryExpression {:column cinfo
                                            :value value})

               :encoded-path
               (map->BinaryExpression {:operator :=
                                       :column cinfo
                                       :value (facts/factpath-to-string value)})

               :path-array
               (map->PathArrayMatch {:column cinfo :path value})

               :queryable-json
               (if (some #(= ::parse/indexed-field-part (:kind %)) path)
                 (map->JsonbPathBinaryExpression {:field column-name
                                                  :column-data cinfo
                                                  :value value
                                                  :operator :=})
                 (map->JsonContainsExpression {:field column-name
                                               :column-data cinfo
                                               :value value}))

               :jsonb-scalar
               (map->BinaryExpression {:operator :=
                                       :column cinfo
                                       :value (su/munge-jsonb-for-storage value)})

               (map->BinaryExpression {:operator :=
                                       :column cinfo
                                       :value value})))

            [["in" column-name ["array" value]]]
            (let [cinfo (get-in query-rec [:projections column-name])]
              (when-not (coll? value)
                (throw (bad-query-ex (tru "Operator 'array' requires a vector argument"))))
              (case (:type cinfo)
                :array
                (throw
                 (bad-query-ex (tru "Operator 'in'...'array' is not supported on array types")))

                :timestamp
                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "timestamp"
                                                                   java.sql.Timestamp
                                                                   (map t/to-timestamp value))})
                :integer
                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "bigint"
                                                                   java.lang.Integer
                                                                   (map int value))})

                :encoded-path
                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "text"
                                                                   String
                                                                   (map facts/factpath-to-string value))})

                :path-array
                (map->PathArrayAnyMatch {:column cinfo :paths value})

                :jsonb-scalar
                (map->InArrayExpression
                  {:column cinfo
                   :value (su/array-to-param "jsonb"
                                             org.postgresql.util.PGobject
                                             (map su/munge-jsonb-for-storage value))})

                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "text"
                                                                   String
                                                                   (map str value))})))

            [[(op :guard #{">" "<" ">=" "<="}) column-name value]]
            ;; FIXME: pass parsed representation down
            (let [[top & _path] (parse/parse-field column-name)
                  {:keys [type] :as cinfo} (get-in query-rec [:projections (:name top)])]
              (cond
                (= :timestamp type)
                (map->BinaryExpression {:operator (keyword op)
                                        :column cinfo
                                        :value (try-parse-timestamp value)})

                (and (number? value) (#{:numeric} type))
                (map->BinaryExpression {:operator (keyword op)
                                        :column cinfo
                                        :value  value})

                (and (number? value) (= :jsonb-scalar type))
                (map->BinaryExpression {:operator (keyword op)
                                        :column cinfo
                                        :value (su/munge-jsonb-for-storage value)})

                (= :queryable-json type)
                (map->JsonbPathBinaryExpression {:field column-name
                                                 :column-data cinfo
                                                 :value (su/munge-jsonb-for-storage value)
                                                 :operator (keyword op)})

                :else
                (throw
                 (bad-query-ex
                  (tru "Argument \"{0}\" and operator \"{1}\" have incompatible types."
                       value op)))))

            [["null?" column-name value]]
            ;; For now, this assumes that foo[5] and match(...) are
            ;; just custom fact names, and doesn't handle foo.5.bar as
            ;; a foo array access, to maintain backward compatibility,
            ;; i.e. create-json-path-extraction doesn't do anything
            ;; but single quote the compoents for a pg json path
            ;; lookup, e.g. json->'x'->'y'.  If we want null? to work
            ;; on nested arrays, then we'll need to distinguish,
            ;; i.e. foo.b'ar[5].baz should become
            ;; foo->'b''ar'->5->'baz'.
            ;; cf. https://www.postgresql.org/docs/11/functions-json.html
            (let [[top & path] (parse/parse-field column-name 0
                                                  {:indexes? false
                                                   :matches? false})
                  cinfo (get-in query-rec [:projections (:name top)])]
              (if (and (= :queryable-json (:type cinfo))
                       (seq path))
                (let [field (:field cinfo)
                      json-path (create-json-path-extraction field (map :name path))]
                  (map->NullExpression {:column (assoc cinfo :field json-path)
                                        :null? value}))
                (map->NullExpression {:column cinfo :null? value})))

            [["~" column-name value]]
            ;; FIXME: pass parsed representation down
            (let [[top] (parse/parse-field column-name)
                  cinfo (get-in query-rec [:projections (:name top)])]
              (case (:type cinfo)
                :array
                (map->ArrayRegexExpression {:column cinfo :value value})

                :queryable-json
                (map->JsonbPathBinaryExpression {:operator (keyword "~")
                                                 :field column-name
                                                 :column-data cinfo
                                                 :value value})

                :jsonb-scalar
                (map->JsonbScalarRegexExpression {:field column-name
                                                  :column-data cinfo
                                                  :value value})

                (map->RegexExpression {:column cinfo :value value})))

            [["~>" column-name value]]
            (let [cinfo (get-in query-rec [:projections column-name])]
              (case (:type cinfo)
                :encoded-path
                (map->RegexExpression {:column cinfo
                                       :value (facts/factpath-regexp-to-regexp
                                               value)})
                :path-array
                (map->PathArrayRegexMatch {:column cinfo :path-rx value})))

            [["and" & expressions]]
            (map->AndExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["or" & expressions]]
            (map->OrExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["not" expression]]
            (map->NotExpression {:clause (user-node->plan-node query-rec expression)})

            [["in" columns subquery-expr]]
            (map->InExpression
             (do
               (s/validate (s/conditional vector? [s/Str] :else s/Str) columns)
               {:column (map #(get-in query-rec [:projections %])
                             (utils/vector-maybe columns))
                :subquery (user-node->plan-node query-rec subquery-expr)}))

            ;; This provides the from capability to replace the select_<entity> syntax from an
            ;; explicit subquery.
            [["from" entity expr & clauses]]
            (create-from-node entity expr clauses)

            [["extract" column]]
            (create-extract-node query-rec column nil)

            [["extract" column ["group_by" & clauses]]]
            (-> query-rec
                (assoc :group-by (group-by-entries->fields query-rec clauses))
                (create-extract-node column nil))

            [["extract" column expr]]
            (create-extract-node query-rec column expr)

            [["extract" column expr ["group_by" & clauses]]]
            (-> query-rec
                (assoc :group-by (group-by-entries->fields query-rec clauses))
                (create-extract-node column expr))

            :else nil))

(defn convert-to-plan
  "Converts the given `user-query` to a query plan that can later be converted into
  a SQL statement"
  [query-rec paging-options user-query]
  (let [plan-node (user-node->plan-node query-rec user-query)]
    (if (instance? Query plan-node)
      plan-node
      (assoc query-rec :where plan-node :paging-options paging-options))))

(declare push-down-context)

(defn- valid-dotted-field?
  [field allowed-json-extract?]
  (let [[top & path] (parse/parse-field field)]
    (and (seq path) (allowed-json-extract? (:name top)))))

(defn unsupported-fields
  [field allowed-fields allowed-json-extracts]
  (let [allowed-json-extract? (set allowed-json-extracts)
        allowed-field? (set allowed-fields)
        supported-call? (set (map #(vector "function" %) (keys pdb-fns->pg-fns)))]
    (remove #(or (allowed-field? %)
                 (supported-call? (take 2 %))
                 (valid-dotted-field? % allowed-json-extract?))
            (ks/as-collection field))))

(defn validate-query-operation-fields
  "Checks if query operation contains allowed fields. Returns error
  message string if some of the fields are invalid.

  Error-action and error-context parameters help in formatting different error messages."
  [field allowed-fields allowed-json-extracts query-name error-action error-context]
  (let [invalid-fields (unsupported-fields field allowed-fields allowed-json-extracts)]
    (when (> (count invalid-fields) 0)
      (format "%s unknown '%s' %s %s%s. Acceptable fields are %s"
              error-action
              query-name
              (if (> (count invalid-fields) 1) "fields" "field")
              (formatter/comma-separated-keywords invalid-fields)
              (if (empty? error-context) "" (str " " error-context))
              (formatter/comma-separated-keywords allowed-fields)))))

(defn valid-operator?
  [operator]
  (or (contains? #{"from" "in" "extract" "subquery" "and"
                   "or" "not" "function" "group_by" "null?"} operator)
      (contains? simple-binary-operators operator)
      (contains? (ks/keyset user-name->query-rec-name) operator)))

(defn validate-extract-filters
  "Validates the operators inside an extract clause. Short-circuits once an
  invalid operator is found"
  [expr]
  (some (fn check-extract-clause [[operator :as clause]]
          (when (vector? clause)
            (if (#{"or" "and"} operator)
              (validate-extract-filters clause)
              (when-not (valid-operator? operator)
                (tru "{0} is not a valid expression for \"extract\"" (pr-str clause))))))
        expr))

(defn annotate-with-context
  "Add `context` as meta on each `node` that is a vector. This associates the
  the query context assocated to each query clause with it's associated context"
  [context]
  (fn [node state]
    (when (vec? node)
      (cm/match [node]
                [["from" entity query & _]]
                (let [query (push-down-context (user-query->logical-obj (str "select_" entity)) query)
                      nested-qc (:query-context (meta query))]
                  {:node (vary-meta (assoc node 2 query)
                                    assoc :query-context nested-qc)
                   :state state
                   :cut true})

                [["extract" column
                  [(subquery-name :guard (set (keys user-name->query-rec-name))) subquery-expression]]]
                (let [subquery-expr (push-down-context (user-query->logical-obj subquery-name) subquery-expression)
                      nested-qc (:query-context (meta subquery-expr))
                      column-validation-message (validate-query-operation-fields
                                                 column
                                                 (queryable-fields nested-qc)
                                                 (projectable-json-fields nested-qc)
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
                   :state (cond-> state column-validation-message (conj column-validation-message))
                   :cut true})

                [["extract" _column [subquery-name :guard (complement #{"not" "group_by" "or" "and"}) _]]]
                (let [underscored-subquery-name (formatter/dashes->underscores subquery-name)
                      error (if (contains? (set (keys user-name->query-rec-name)) underscored-subquery-name)
                              (tru "Unsupported subquery `{0}` - did you mean `{1}`?" subquery-name underscored-subquery-name)
                              (tru "Unsupported subquery `{0}`" subquery-name))]
                  {:node nil
                   :state (conj state error)
                   :cut true})

                [["subquery" relationship expr]]
                (let [subquery-expr (push-down-context
                                     (user-query->logical-obj (str "select_" relationship))
                                     expr)]

                  {:node (vary-meta ["subquery" relationship subquery-expr]
                                    assoc :query-context context)
                   :state state
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
            (let [{:keys [alias dotted-fields] :as query-context} (:query-context (meta node))
                  qfields (queryable-fields query-context)]
              (when-not (or (vec? field)
                            (= "node_state" field)
                            (contains? (set qfields) field)
                            (some #(re-matches % field) (map re-pattern dotted-fields)))
                {:node nil
                 :state (conj
                          state
                          (if (empty? qfields)
                            (tru "''{0}'' is not a queryable object for {1}. Entity {2} has no queryable objects"
                                 field alias alias)
                            (tru "''{0}'' is not a queryable object for {1}. Known queryable objects are {2}"
                                 field alias (formatter/comma-separated-keywords qfields))))}))

            ; This validation is only for top-level extract operator
            ; For in-extract operator validation, please see annotate-with-context function
            [["extract" field & expr]]
            (let [query-context (:query-context (meta node))
                  extractable-fields (projectable-fields query-context)
                  extractable-json-fields (projectable-json-fields query-context)
                  column-validation-message (or (validate-query-operation-fields
                                                  field
                                                  extractable-fields
                                                  extractable-json-fields
                                                  (:alias query-context)
                                                  "Can't extract" "")
                                                (validate-extract-filters expr))]
              (when column-validation-message
                {:node nil
                 :state (conj state column-validation-message)}))

            [["in" field ["array" _]]]
            (let [{:keys [alias dotted-fields] :as query-context} (:query-context (meta node))
                  qfields (queryable-fields query-context)]
              (when-not (or (vec? field)
                            (contains? (set qfields) field)
                            (some #(re-matches % field) (map re-pattern dotted-fields)))
                {:node nil
                 :state (conj state
                              (if (empty? qfields)
                                (tru "''{0}'' is not a queryable object for {1}. Entity {1} has no queryable objects"
                                     field alias)
                                (tru "''{0}'' is not a queryable object for {1}. Known queryable objects are {2}"
                                     field alias (formatter/comma-separated-keywords qfields))))}))

            [["in" field & _]]
            (let [query-context (:query-context (meta node))
                  column-validation-message (validate-query-operation-fields
                                             field
                                             (queryable-fields query-context)
                                             []
                                             (:alias query-context)
                                             "Can't match on" "for 'in'")]
              (when column-validation-message
                {:node nil
                 :state (conj state column-validation-message)}))

            :else nil))

(defn ops-to-lower
  "Lower cases operators (such as and/or)."
  [node state]
  (cm/match [node]
            [[(op :guard (comp valid-operator? str/lower-case)) & stmt-rest]]
            {:node (with-meta (vec (apply vector (str/lower-case op) stmt-rest))
                              (meta node))
             :state state}
            :else nil))

(defn push-down-context
  "Pushes the top level query context down to each query node, throws a
  bad-query-ex if any unrecognized fields appear in the query"
  [context user-query]
  (let [{annotated-query :node
         errors :state} (zip/pre-order-visit (zip/tree-zipper user-query)
                                             []
                                             [(annotate-with-context context)
                                              validate-query-fields
                                              ops-to-lower])]
    (when (seq errors)
      (throw (bad-query-ex (str/join \newline errors))))

    annotated-query))

(defn optimize-user-query-node [user-query-node]
  (cm/match
   [user-query-node]

   ;; fact name-and-value query
   [["extract" (extract-col :guard #{"certname" "environment"})
     ["select_facts"
      ["and"
       ["=" "name" fact-name]
       [op "value" (fact-value :guard (complement vector?))]]]]]
   ["extract" extract-col
    ["select_inventory"
     [op (str "facts." fact-name) fact-value]]]

   ;; fact name-and-value query with type restriction
   [["extract" (extract-col :guard #{"certname" "environment"})
     ["select_facts"
      ["and"
       ["=" "name" fact-name]
       ["and"
        ["=" ["function" "jsonb_typeof" "value"] _]
        [op "value" (fact-value :guard (complement vector?))]]]]]]
   ;; type restrictions don't work on the inventory endpoint; favor better perf
   ;; here over slightly strange behavior in the corner case where a fact has
   ;; string data on one host and numeric on another.
   ["extract" extract-col
    ["select_inventory"
     [op (str "facts." fact-name) fact-value]]]

   :else
   user-query-node))

(defn optimize-user-query [user-query]
  (walk/prewalk optimize-user-query-node user-query))


(defn name-constraint
  "If the query clause is either a simple constraint on 'name' or a pure
  conjunction containing such a constraint, return the first fact name."
  [user-query-clause]
  (or
   (and (= (take 2 user-query-clause) ["=" "name"])
        (nth user-query-clause 2))
   (and (= (first user-query-clause) "and")
        (some name-constraint (rest user-query-clause)))))

(defn rewrite-fact-query
  "Rewrite fact queries that constrain the fact name to directly
  extract the value using the json -> operator, by changing
  the :selection field of the query-rec."
  [query-rec user-query]
  (if-not (= query-rec facts-query)
    [query-rec user-query]
    (cm/match
     [user-query]
     [(:or (clause :guard name-constraint)
           ["extract" _ (clause :guard name-constraint) & _])]
     ;; If there are multiple fact-names used in a conjunction inside of
     ;; 'clause', fact-name will just choose one of them. But the query is
     ;; degenerate anyway. We'll end up with a query that says "where 'foo'='foo' and 'foo'='bar'",
     ;; and thus still have no results.
     (let [fact-name (name-constraint clause)]
       [(update facts-query :selection assoc
                :from [[{:select [:certname :environment_id
                                  [[:cast [:raw "?" ] :text] :key]
                                  [[[:coalesce
                                     [:-> :volatile [:raw "?"]]
                                     [:-> :stable [:raw "?"]]]]
                                   :value]]
                         :from :factsets
                         :where [[:? [:|| :stable :volatile] [:raw "?"]]]}
                        :fs]]
                :selection-params [fact-name fact-name fact-name fact-name])
        user-query])
     :else
     [query-rec user-query])))


;; Top-level parsing

(def experimental-entities
  #{:event-counts :aggregate-event-counts})

(defn warn-experimental
  "Show a warning if the endpoint is experimental."
  [entity]
  (when (contains? experimental-entities entity)
    (log/warn (trs "The {0} entity is experimental and may be altered or removed in the future."
                   (name entity)))))

(defn paging-clause?
  [v]
  (contains? #{"limit" "order_by" "offset"} (first v)))

(defn parse-query-context
  "Parses a top-level query with a 'from', validates it and returns the entity and remaining-query in
   a map."
  [query warn]
  (cm/match
    query
    ["from" (entity-str :guard #(string? %)) & remaining-query]
    (let [remaining-query
          (cm/match
           remaining-query

           [(c :guard paging-clause?) & clauses]
           (let [paging-clauses (create-paging-map (cons c clauses))]
             {:paging-clauses paging-clauses :query []})

           [(q :guard vector?)]
           {:query q}

           [(q :guard vector?) & clauses]
           (let [paging-clauses (create-paging-map clauses)]
             {:query q :paging-clauses paging-clauses})

           []
           {:query []}
           :else (throw
                  (bad-query-ex
                   (tru "`from` only accepts a query as an optional second argument."))))
          entity (keyword (formatter/underscores->dashes entity-str))]
      (when warn
        (warn-experimental entity))
      {:remaining-query (:query remaining-query)
       :paging-clauses (:paging-clauses remaining-query)
       :entity entity})

    :else (throw
           (bad-query-ex
            (trs "This query must be of the form [\"from\", <entity>, (<optional-query>)].")))))

(defn simple-coll? [x] (and (coll? x) (not (map? x))))

(defn extract-deps [m plan]
  (cm/match
   m

   ;; operator-field-clause (see query-eng-test)
   {:operator _
    :column {:field (_f :guard keyword?)
             :join-deps (deps :guard set?)}}
   deps

   ;; jsonb-type-equal-clause (see compiler above and query-eng-test)
   ;; {:operator "=" :function "jsonb_typeof" :args [maybe-field] :value "?"}
   {:function "jsonb_typeof"
    :operator "="
    :args ([(field :guard string?)] :seq)}
   (if-let [deps (get-in plan [:projections field :join-deps])]
     deps
     (throw (ex-info "Unexpected jsonb_typeof when extracting deps"
                     {:kind ::cannot-drop-joins
                      ::why :unexpected-jsonb-typeof})))

   ;; subquery-clause
   {:subquery _
    :column ([& columns] :seq)}
   (if (and (every? map? columns)
            (every? keyword? (map :field columns))
            (every? set? (map :join-deps columns)))
     (apply set/union (map :join-deps columns))
     (throw (ex-info "Unexpected subquery when extracting deps"
                     {:kind ::cannot-drop-joins
                      ::why :unexpected-subquery})))

   ;; dotted-match-filter-clause (as one source -- see tests)
   ;; same shape as dotted-filter-clause (cf. extract-where-deps-from-clause)
   {:field _
    :column-data {:join-deps (deps :guard set?)}}
   deps

  :else
  (throw (ex-info "Unexpected plan node when extracting deps"
                  {:kind ::cannot-drop-joins
                   ::why :unexpected-plan-node}))))

(defn extract-where-deps-from-clause
  [clause plan]
  (cond
    (:column clause)
    (let [col (:column clause)]
      (cond
        ;; e.g.
        ;; {:operator :=,
        ;;  :column
        ;;  {:type :string,
        ;;   :queryable? true,
        ;;   :field :fs.certname,
        ;;   :join-deps #{:fs}},
        ;;  :value "?"}
        ;; single-column-clause
        (:join-deps col) (:join-deps col)

        ;; e.g.
        ;; {:column
        ;;  ({:type :string,
        ;;    :queryable? true,
        ;;    :field :certnames.certname,
        ;;    :join-deps #{:certnames}}),
        ;;  :subquery ...}
        ;; multiple-column-clause
        (and (simple-coll? col)
             (every? map? col)
             (every? keyword? (map :field col))
             (every? set? (map :join-deps col)))
        (apply set/union (map :join-deps col))

        :else
        (throw (ex-info (str "Unexpected column when extracting deps: "
                             (class clause) " " clause)
                        {:kind ::cannot-drop-joins
                         ::why :unexpected-column}))))
    ;; e.g.
    ;; {:clauses
    ;;  ({:operator :=,
    ;;    :column
    ;;    {:type :string,
    ;;     :queryable? true,
    ;;     :field :fs.certname,
    ;;     :join-deps #{:fs}},
    ;;    :value "?"}
    ;;   {:clause
    ;;    {:column
    ;;     ({:type :string,
    ;;       :queryable? true,
    ;;       :field :fs.certname,
    ;;       :join-deps #{:fs}}),
    ;;     :subquery ...}})}
    ;; clauses-clause
    (:clauses clause)
    (let [c (:clauses clause)]
      (if (simple-coll? c)
        (apply set/union (map #(extract-deps % plan) c))
        (throw (ex-info (str "Unexpected clauses when extracting deps: "
                             (class clause) " " clause)
                        {:kind ::cannot-drop-joins
                         ::why :unexpected-clauses}))))
    ;; e.g.
    ;; {:clause
    ;;  {:column
    ;;   ({:type :string,
    ;;     :queryable? true,
    ;;     :field :certnames.certname,
    ;;     :join-deps #{:certnames}}),
    ;;   :subquery ... }}
    ;; clause-clause
    (:clause clause)
    (if (map? clause)
      (extract-deps (:clause clause) plan)
      (throw (ex-info (str "Unexpected clause when extracting deps: "
                           (class clause) " " clause)
                      {:kind ::cannot-drop-joins
                       ::why :unexpected-clause})))

    ;; e.g.
    ;; {:field "facts",
    ;;  :column-data
    ;;  {:type :queryable-json,
    ;;   :projectable-json? true,
    ;;   :queryable? true,
    ;;   :field {:s "(fs.stable||fs.volatile)"},
    ;;   :field-type :raw,
    ;;   :join-deps #{:fs}},
    ;;  :value ("?" "?"),
    ;;  :operator :>}
    ;; dotted-filter-clause (cf. extract-deps)
    (and (:field clause) (set? (get-in clause [:column-data :join-deps])))
    (get-in clause [:column-data :join-deps])

    :else
    (throw (ex-info (str "Unexpected where clause when extracting deps: "
                         (class clause) " " clause)
                    {:kind ::cannot-drop-joins
                     ::why :unexpected-where}))))

(defn extract-where-deps [plan]
  (if-let [where (:where plan)]
    (if-let [clauses (:clauses where)]
      (apply set/union (map #(extract-where-deps-from-clause % plan)
                            clauses))
      (extract-where-deps-from-clause where plan))
    #{}))

(defn extract-function-deps [plan]
  (apply set/union (map :join-deps (:call plan))))

;; FIXME: add dotted test?
(defn required-by-projections [plan]
  (let [proj-info (:projections plan)
        fields (or (:project-fields plan)
                   ;; If we wanted to generalize this to apply to the
                   ;; plan recursively, i.e. subqueries, could
                   ;; convert-to-plan be relevant?
                   (->> (:projected-fields plan)
                        ;; Is this not supposed to be a map already?
                        ;; i.e. wondering if we might have a
                        ;; conversion bug elsewhere...
                        (into {})
                        (remove (comp :unprojectable? val))
                        keys))
        ;; Now we need just the base name, i.e. facts.kernel -> facts
        basename #(-> % parse/parse-field first :name)
        required-joins (map #(get-in proj-info [% :join-deps])
                            (map basename fields))]
    (when (some nil? required-joins)
      (throw (ex-info
              (tru "Could not drop-joins from {0} query. One or more projections were missing :join-deps {1}"
                   (name (::which-query plan))
                   (pr-str required-joins))
              {:kind ::cannot-drop-joins
               ::why :missing-join-deps-information})))
    (apply set/union required-joins)))

(defn drop-local-unused-joins-from-query
  [plan]
  (if-not (:can-drop-unused-joins? plan)
    (do
      (log/debug (trs "Not dropping unused joins from query (disallowed)"))
      plan)
    (if (and (:call plan)
             (or (not= 1 (count (:call plan)))
                 (not= "count" (:function (first (:call plan))))))
      (throw (ex-info "Can only optimize queries with a single count function call"
                      {:kind ::cannot-drop-joins
                       ::why :unsupported-function-calls}))
      (let [proj-reqs (required-by-projections plan)]
        (if (and (empty? proj-reqs)
                 (empty? (:call plan)))
          plan
          (let [where-reqs (extract-where-deps plan)
                function-reqs (extract-function-deps plan)
                required? (set/union proj-reqs where-reqs function-reqs)
                join-key? (if (empty? (:call plan))
                            #{:cross-join
                              :full-join
                              :join
                              :left-join
                              :merge-full-join
                              :merge-join
                              :merge-left-join
                              :merge-right-join
                              :right-join}
                            #{:left-join})
                need-join? (fn [[table _spec]]
                             ;; table e.g. :factsets or [:factsets :fs]
                             ;; spec e.g. [:= :certnames.certname :fs.certname]
                             (let [join-name (cond-> table (coll? table) second)]
                               (when-not (keyword? join-name)
                                 (throw
                                  (bad-query-ex
                                   (tru "{0} join in {1} query was expected to be a keyword"
                                        join-name (name (::which-query plan))))))
                               (required? join-name)))
                drop-joins (fn [result k v]
                             (if-not (join-key? k)
                               result
                               (assoc result
                                      k (vec (apply concat
                                                    (filter need-join?
                                                            (partition 2 v)))))))
                selection (reduce-kv drop-joins
                                     (:selection plan)
                                     (:selection plan))]
            (assoc plan :selection selection)))))))

(defn drop-all-unused-joins [{:keys [plan] :as incoming}]
  (try
    (log/debug (trs "Attempting to drop unused joins from query"))
    (let [{:keys [node _state]} (zip/post-order-visit (zip/tree-zipper plan)
                                                      []
                                                      [(fn [node state]
                                                         (when (instance? Query node)
                                                           {:node (drop-local-unused-joins-from-query node) :state state}))])]
      (assoc incoming :plan node))
    (catch ExceptionInfo ex
      (if (= ::cannot-drop-joins (:kind (ex-data ex)))
        (do
          (log/debug (str ex))
          incoming)
        (throw ex)))))

(def default-explain-form
  "EXPLAIN (VERBOSE,ANALYZE,BUFFERS,FORMAT JSON) ")

(defn wrap-with-explain [query explain-options]
  (if (= explain-options :analyze)
    (str default-explain-form query)
    query))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compile-query
  "Given a user provided query and a Query instance, converts the user
  provided query to the requested target.  Current targets are
  either :parameterized-plan or :sql.  For :sql, returns the SQL and
  extracted parameters, to be used in a prepared statement.
  For :parameterized-plan, returns the final plan and parameters that
  would be used to generate the :sql result."
  [query-rec user-query {:keys [include_total explain] :as options} target]
  ;; Call the query-rec so we can evaluate query-rec functions
  ;; which depend on the db connection type
  (let [allowed-fields (map keyword (queryable-fields query-rec))
        validated-options (some->> options
                                (paging/validate-order-by! allowed-fields)
                                (paging/dealias-order-by query-rec))
        [query-rec user-query] (rewrite-fact-query query-rec user-query)
        optimize-joins (if (or always-enable-drop-unused-joins?
                               ;; Why paging-options?  See PDB-1936
                               (:optimize_drop_unused_joins validated-options
                                                            enable-drop-unused-joins-by-default?))
                         drop-all-unused-joins
                         identity)
        parameterized-plan (->> user-query
                                (push-down-context query-rec)
                                expand-user-query
                                optimize-user-query
                                (convert-to-plan query-rec validated-options)
                                extract-all-params
                                optimize-joins)]
    (case target
      :parameterized-plan parameterized-plan
      :sql (let [{:keys [plan params]} parameterized-plan
                 {:keys [limit offset order_by]} validated-options
                 paged-plan (cond-> plan
                                limit (assoc :limit [:inline limit])
                                offset (assoc :offset [:inline offset])
                                ;; TODO: switching from underscore to dash here is ugly
                                ;; move it to user-parameter handling in query_eng
                                (seq order_by) (assoc :order-by (munge-query-ordering order_by)))
                 paged-sql (wrap-with-explain (plan->sql paged-plan validated-options) explain)
                 ;; This plan omits limit/offset so that it gets a full count of the results
                 ;; it omits order-by because it is unecessary for a count-only query.
                 count-sql (plan->sql plan validated-options)]
             (cond-> {:results-query (apply vector paged-sql params)}
               include_total (assoc :count-query
                                    (apply vector (jdbc/count-sql count-sql)
                                           params)))))))

(defn compile-user-query->sql
  "Given a user provided query and a Query instance, convert the
   user provided query to SQL and extract the parameters, to be used
   in a prepared statement."
  ([query-rec user-query]
   (compile-query query-rec user-query {} :sql))
  ([query-rec user-query options]
   (compile-query query-rec user-query options :sql)))
