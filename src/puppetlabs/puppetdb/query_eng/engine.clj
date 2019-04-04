(ns puppetlabs.puppetdb.query-eng.engine
  (:require [clojure.core.match :as cm]
            [clojure.string :as str]
            [puppetlabs.i18n.core :refer [tru trs]]
            [clojure.set :refer [map-invert]]
            [clojure.tools.logging :as log]
            [honeysql.core :as hcore]
            [honeysql.helpers :as hsql]
            [honeysql.types :as htypes]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.scf.storage-utils :as su]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [puppetlabs.puppetdb.zip :as zip]
            [schema.core :as s]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [clojure.string :as string]
            [clojure.walk :as walk])
  (:import [honeysql.types SqlCall SqlRaw]
           [org.postgresql.util PGobject]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defn validate-dotted-field
  [dotted-field]
  (and (string? dotted-field) (re-find #"(facts|trusted)\..+" dotted-field)))

(def field-schema (s/cond-pre s/Keyword
                              SqlCall SqlRaw
                              {:select s/Any s/Any s/Any}))

(def certname-relations
  {"factsets" {:columns ["certname"]}
   "reports" {:columns ["certname"]}
   "package-inventory" {:columns ["certname"]}
   "inventory" {:columns ["certname"]}
   "catalogs" {:columns ["certname"]}
   "nodes" {:columns ["certname"]}
   "facts" {:columns ["certname"]}
   "fact_contents" {:columns ["certname"]}
   "events" {:columns ["certname"]}
   "edges" {:columns ["certname"]}
   "resources" {:columns ["certname"]}
   "certname_fact_expiration" {:columns ["certid"]}})

(def type-coercion-matrix
  {:string {:numeric (su/sql-cast "int")
            :boolean (su/sql-cast "bool")
            :string hcore/raw
            :jsonb-scalar (su/sql-cast "jsonb")
            :timestamp (su/sql-cast "timestamptz")}
   :jsonb-scalar {:numeric (su/jsonb-scalar-cast "numeric")
                  :jsonb-scalar hcore/raw
                  :boolean (comp (su/sql-cast "boolean") (su/sql-cast "text"))
                  :string (su/jsonb-scalar-cast "text")}
   :path {:path hcore/raw}
   :json {:json hcore/raw}
   :boolean {:string (su/sql-cast "text")
             :boolean hcore/raw}
   :numeric {:string (su/sql-cast "text")
             :numeric hcore/raw}
   :timestamp {:string (su/sql-cast "text")
               :timestamp hcore/raw}
   :array {:array hcore/raw}
   :queryable-json {:queryable-json hcore/raw}})

(defn convert-type
  [column from to]
  ((-> type-coercion-matrix from to) column))

(def column-schema
  "Column information: [\"value\" {:type :string :field fv.value_string ...}]"
  {:type s/Keyword :field field-schema s/Any s/Any})

(def projection-schema
  "Named projection: [\"value\" {:type :string :field fv.value_string ...}]"
  [(s/one s/Str "name") (s/one column-schema "column")])

(s/defrecord Query
    [projections :- {s/Str column-schema}
     selection
     source-table :- s/Str
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
  [column-keyword]
  (->> column-keyword
       name
       su/sql-hash-as-str
       hcore/raw))

(defn hsql-hash-as-href
  [entity parent child]
  (hcore/raw (str "format("
                  (str "'/pdb/query/v4/" (name parent) "/%s/" (name child) "'")
                  ", "
                  entity
                  ")")))

(defn hsql-uuid-as-str
  [column-keyword]
  (-> column-keyword name (str "::text") hcore/raw))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queryable Entities
(def inventory-query
  "Query for inventory"
  (map->Query {:projections {"certname" {:type :string
                                         :queryable? true
                                         :field :certnames.certname}
                             "timestamp" {:type :timestamp
                                          :queryable? true
                                          :field :fs.timestamp}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :environments.environment}
                             "facts" {:type :queryable-json
                                      :queryable? true
                                      :field (hcore/raw "(fs.stable||fs.volatile)")}
                             "trusted" {:type :queryable-json
                                        :queryable? true
                                        :field (hcore/raw "(fs.stable||fs.volatile)->'trusted'")}}

               :selection {:from [[:factsets :fs]]
                           :left-join [:environments
                                       [:= :fs.environment_id :environments.id]

                                       :producers
                                       [:= :fs.producer_id :producers.id]

                                       :certnames
                                       [:= :fs.certname :certnames.certname]]}

               :alias "inventory"
               :relationships certname-relations

               :dotted-fields ["facts\\..*" "trusted\\..*"]
               :entity :inventory
               :subquery? false}))

(def nodes-query-base
  {:projections {"certname" {:type :string
                             :queryable? true
                             :field :certnames.certname}
                 "deactivated" {:type :string
                                :queryable? true
                                :field :certnames.deactivated}
                 "expired" {:type :timestamp
                            :queryable? true
                            :field :certnames.expired}
                 "facts_environment" {:type :string
                                      :queryable? true
                                      :field :facts_environment.environment}
                 "catalog_timestamp" {:type :timestamp
                                      :queryable? true
                                      :field :catalogs.timestamp}
                 "facts_timestamp" {:type :timestamp
                                    :queryable? true
                                    :field :fs.timestamp}
                 "report_timestamp" {:type :timestamp
                                     :queryable? true
                                     :field :reports.end_time}
                 "latest_report_hash" {:type :string
                                       :queryable? true
                                       :field (hsql-hash-as-str
                                               :reports.hash)}
                 "latest_report_noop" {:type :boolean
                                       :queryable? true
                                       :field :reports.noop}
                 "latest_report_noop_pending" {:type :boolean
                                               :queryable? true
                                               :field :reports.noop_pending}
                 "latest_report_status" {:type :string
                                         :queryable? true
                                         :field :report_statuses.status}
                 "latest_report_corrective_change" {:type :boolean
                                                    :queryable? true
                                                    :field :reports.corrective_change}
                 "latest_report_job_id" {:type :string
                                         :queryable? true
                                         :field :reports.job_id}
                 "cached_catalog_status" {:type :string
                                          :queryable? true
                                          :field :reports.cached_catalog_status}
                 "catalog_environment" {:type :string
                                        :queryable? true
                                        :field :catalog_environment.environment}
                 "report_environment" {:type :string
                                       :queryable? true
                                       :field :reports_environment.environment}}

   :relationships certname-relations

   :selection {:from [:certnames]
               :left-join [:catalogs
                           [:= :catalogs.certname :certnames.certname]

                           [:factsets :fs]
                           [:= :certnames.certname :fs.certname]

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

   :source-table "certnames"
   :alias "nodes"
   :subquery? false})

(def nodes-query
  "Query for nodes entities, mostly used currently for subqueries"
  (map->Query nodes-query-base))

(def nodes-query-with-fact-expiration
  "Query for nodes entities, mostly used currently for subqueries"
  (map->Query (-> nodes-query-base
                  (assoc-in [:projections "expires_facts"]
                            {:type :boolean
                             :queryable? true
                             :field (hcore/raw "coalesce(certname_fact_expiration.expire, true)")})
                  (assoc-in [:projections "expires_facts_updated"]
                            {:type :timestamp
                             :queryable? true
                             :field :certname_fact_expiration.updated})
                  (update-in [:selection :left-join]
                             #(conj %
                                    :certname_fact_expiration
                                    [:= :certnames.id :certname_fact_expiration.certid])))))

(def resource-params-query
  "Query for the resource-params query, mostly used as a subquery"
  (map->Query {:projections {"res_param_resource" {:type :string
                                                   :queryable? true
                                                   :field (hsql-hash-as-str :resource)}
                             "res_param_name" {:type :string
                                               :queryable? true
                                               :field :name}
                             "res_param_value" {:type :string
                                                :queryable? true
                                                :field :value}}
               :selection {:from [:resource_params]}

               :source-table "resource_params"
               :alias "resource_params"
               :subquery? false}))

(def fact-paths-query
  "Query for the resource-params query, mostly used as a subquery"
  (map->Query {:projections {"type" {:type :string
                                     :queryable? true
                                     :field :vt.type}
                             "path" {:type :path
                                     :queryable? true
                                     :field :path}
                             "name" {:type :string
                                     :queryable? true
                                     :field :name}
                             "depth" {:type :numeric
                                      :queryable? true
                                      :query-only? true
                                      :field :fp.depth}}
               :selection {:from [[:fact_paths :fp]]
                           :join [[:value_types :vt]
                                  [:= :fp.value_type_id :vt.id]]
                           :where [:!= :fp.value_type_id 5]}

               :relationships {;; Children - direct
                               "facts" {:columns ["name"]}
                               "fact_contents" {:columns ["path"]}}

               :source-table "fact_paths"
               :alias "fact_paths"
               :subquery? false}))

(def fact-names-query
  (map->Query {:projections {"name" {:type :string
                                     :queryable? true
                                     :field :name}}
               :selection {:from [[:fact_paths :fp]]
                           :modifiers [:distinct]}
               :source-table "fact_paths"
               :alias "fact_names"
               :subquery? false}))

(def facts-query
  "Query structured facts."
  (map->Query {:projections {"certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.environment}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fs.key}
                             "value" {:type :jsonb-scalar
                                      :queryable? true
                                      :field :fs.value}}
               :selection {:from [[(hcore/raw
                                    (str "(select certname,"
                                         "        environment_id,"
                                         "        (jsonb_each((stable||volatile))).*"
                                         "  from factsets)"))
                                   :fs]]
                           :left-join [[:environments :env]
                                       [:= :fs.environment_id :env.id]]}

               :relationships (merge certname-relations
                                     {"environments" {:local-columns ["environment"]
                                                      :foreign-columns ["name"]}
                                      "fact_contents" {:columns ["certname" "name"]}})

               :alias "facts"
               :source-table "factsets"
               :entity :facts
               :subquery? false}))

(def fact-contents-query
  "Query for fact nodes"
  (map->Query {:projections {"certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.environment}
                             "path" {:type :path
                                     :queryable? true
                                     :field :fs.path}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fs.name}
                             "value" {:type :jsonb-scalar
                                      :queryable? true
                                      :field :value}}
               :selection {:from [[(hcore/raw
                                    (str "(select certname,"
                                         "        jsonb_extract_path(stable||volatile,"
                                         "                           variadic path_array)"
                                         "          as value,"
                                         "        path,"
                                         "        name,"
                                         "        environment_id"
                                         "   from factsets"
                                         "     cross join (select distinct on (path)"
                                         "                        path,"
                                         "                        path_array,"
                                         "                        name from fact_paths) distinct_paths"
                                         "   where jsonb_extract_path(stable||volatile,"
                                         "                            variadic path_array)"
                                         "           is not null"
                                         "         and jsonb_typeof(jsonb_extract_path(stable||volatile,"
                                         "                                             variadic path_array))"
                                         "               <> 'object')"))
                                   :fs]]
                           :left-join [[:environments :env]
                                       [:= :fs.environment_id :env.id]]}

               :relationships (merge certname-relations
                                     {"facts" {:columns ["certname" "name"]}
                                      "environments" {:local-columns ["environment"]
                                                      :foreign-columns ["name"]}})

               :alias "fact_nodes"
               :source-table "factsets"
               :subquery? false}))

(def report-logs-query
  "Query intended to be used by the `/reports/<hash>/logs` endpoint
  used for digging into the logs for a specific report."
  (map->Query {:projections {"logs" {:type :json
                                     :queryable? false
                                     :field (h/coalesce :logs
                                                        (h/scast :logs_json :jsonb))}
                             "hash" {:type :string
                                     :queryable? true
                                     :query-only? true
                                     :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:reports]}

               :alias "logs"
               :subquery? false
               :entity :reports
               :source-table "reports"}))

(def report-metrics-query
  "Query intended to be used by the `/reports/<hash>/metrics` endpoint
  used for digging into the metrics for a specific report."
  (map->Query {:projections {"metrics" {:type :json
                                        :queryable? false
                                        :field (h/coalesce :reports.metrics
                                                           (h/scast :reports.metrics_json :jsonb))}
                             "hash" {:type :string
                                     :queryable? true
                                     :query-only? true
                                     :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:reports]}

               :alias "metrics"
               :subquery? false
               :entity :reports
               :source-table "reports"}))

(def reports-query
  "Query for the reports entity"
  (map->Query
    {:projections
     {"hash" {:type :string
              :queryable? true
              :field (hsql-hash-as-str :reports.hash)}
      "certname" {:type :string
                  :queryable? true
                  :field :reports.certname}
      "noop_pending" {:type :boolean
                      :queryable? true
                      :field :reports.noop_pending}
      "puppet_version" {:type :string
                        :queryable? true
                        :field :reports.puppet_version}
      "report_format" {:type :numeric
                       :queryable? true
                       :field :reports.report_format}
      "configuration_version" {:type :string
                               :queryable? true
                               :field :reports.configuration_version}
      "start_time" {:type :timestamp
                    :queryable? true
                    :field :reports.start_time}
      "end_time" {:type :timestamp
                  :queryable? true
                  :field :reports.end_time}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :reports.producer_timestamp}
      "producer" {:type :string
                  :queryable? true
                  :field :producers.name}
      "corrective_change" {:type :string
                           :queryable? true
                           :field :reports.corrective_change}
      "metrics" {:type :json
                 :queryable? false
                 :field {:select [(h/row-to-json :t)]
                         :from [[{:select
                                  [[(h/coalesce :metrics (h/scast :metrics_json :jsonb)) :data]
                                   [(hsql-hash-as-href (su/sql-hash-as-str "hash") :reports :metrics)
                                    :href]]} :t]]}}
      "logs" {:type :json
              :queryable? false
              :field {:select [(h/row-to-json :t)]
                      :from [[{:select
                               [[(h/coalesce :logs (h/scast :logs_json :jsonb)) :data]
                                [(hsql-hash-as-href (su/sql-hash-as-str "hash") :reports :logs)
                                 :href]]} :t]]}}
      "receive_time" {:type :timestamp
                      :queryable? true
                      :field :reports.receive_time}
      "transaction_uuid" {:type :string
                          :queryable? true
                          :field (hsql-uuid-as-str :reports.transaction_uuid)}
      "catalog_uuid" {:type :string
                      :queryable? true
                      :field (hsql-uuid-as-str :reports.catalog_uuid)}
      "noop" {:type :boolean
              :queryable? true
              :field :reports.noop}
      "code_id" {:type :string
                 :queryable? true
                 :field :reports.code_id}
      "job_id" {:type :string
                :queryable? true
                :field :reports.job_id}
      "cached_catalog_status" {:type :string
                               :queryable? true
                               :field :reports.cached_catalog_status}
      "environment" {:type :string
                     :queryable? true
                     :field :environments.environment}
      "status" {:type :string
                :queryable? true
                :field :report_statuses.status}
      "latest_report?" {:type :string
                        :queryable? true
                        :query-only? true}
      "resource_events" {:type :json
                         :queryable? false
                         :field {:select [(h/row-to-json :event_data)]
                                 :from [[{:select
                                          [[(json-agg-row :t) :data]
                                           [(hsql-hash-as-href (su/sql-hash-as-str "hash") :reports :events) :href]]
                                          :from [[{:select
                                                   [:re.status
                                                    :re.timestamp
                                                    :re.resource_type
                                                    :re.resource_title
                                                    :re.property
                                                    :re.corrective_change
                                                    (h/scast :re.new_value :jsonb)
                                                    (h/scast :re.old_value :jsonb)
                                                    :re.message
                                                    :re.file
                                                    :re.line
                                                    :re.containment_path
                                                    :re.containing_class]
                                                   :from [[:resource_events :re]]
                                                   :where [:= :reports.id :re.report_id]} :t]]}
                                         :event_data]]}}}
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
     :source-table "reports"}))

(def catalog-query
  "Query for the top level catalogs entity"
  (map->Query
    {:projections
     {"version" {:type :string
                 :queryable? true
                 :field :c.catalog_version}
      "certname" {:type :string
                  :queryable? true
                  :field :c.certname}
      "hash" {:type :string
              :queryable? true
              :field (hsql-hash-as-str :c.hash)}
      "transaction_uuid" {:type :string
                          :queryable? true
                          :field (hsql-uuid-as-str :c.transaction_uuid)}
      "catalog_uuid" {:type :string
                      :queryable? true
                      :field (hsql-uuid-as-str :c.catalog_uuid)}
      "code_id" {:type :string
                 :queryable? true
                 :field :c.code_id}
      "job_id" {:type :string
                :queryable? true
                :field :c.job_id}
      "environment" {:type :string
                     :queryable? true
                     :field :e.environment}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :c.producer_timestamp}
      "producer" {:type :string
                  :queryable? true
                  :field :producers.name}
      "resources" {:type :json
                   :queryable? false
                   :field {:select [(h/row-to-json :resource_data)]
                           :from [[{:select [[(json-agg-row :t) :data]
                                             [(hsql-hash-as-href "c.certname" :catalogs :resources) :href]]
                                    :from [[{:select [[(hsql-hash-as-str :cr.resource) :resource]
                                                      :cr.type :cr.title :cr.tags :cr.exported :cr.file :cr.line
                                                      [(h/scast :rpc.parameters :json) :parameters]]
                                             :from [[:catalog_resources :cr]]
                                             :join [[:resource_params_cache :rpc]
                                                    [:= :rpc.resource :cr.resource]]
                                             :where [:= :cr.certname_id :certnames.id]}
                                            :t]]}
                                   :resource_data]]}}
      "edges" {:type :json
               :queryable? false
               :field {:select [(h/row-to-json :edge_data)]
                       :from [[{:select [[(json-agg-row :t) :data]
                                         [(hsql-hash-as-href "c.certname" :catalogs :edges) :href]]
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
                               :edge_data]]}}}

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
     :source-table "catalogs"}))

(def edges-query
  "Query for catalog edges"
  (map->Query {:projections {"certname" {:type :string
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
               :source-table "edges"}))

(def resources-query
  "Query for the top level resource entity"
  (map->Query {:projections {"certname" {:type  :string
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
                                      :query-only? true}
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
                                           :queryable? true
                                           :field :rpc.parameters}}

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
               :source-table "catalog_resources"}))

(def report-events-query
  "Query for the top level reports entity"
  (map->Query {:projections {"certname" {:type :string
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
                                               :query-only? true}
                             "report_id" {:type :numeric
                                          :queryable? false
                                          :query-only? true
                                          :field :events.report_id}}
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
               :source-table "resource_events"}))

(def inactive-nodes-query
  (map->Query {:projections {"certname" {:type :string
                                         :queryable? true
                                         :field :inactive_nodes.certname}}
               :selection {:from [:inactive_nodes]}
               :subquery? false
               :alias "inactive_nodes"
               :source-table "inactive_nodes"}))

(def latest-report-query
  "Usually used as a subquery of reports"
  (map->Query {:projections {"latest_report_hash" {:type :string
                                                   :queryable? true
                                                   :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:certnames]
                           :join [:reports
                                  [:and
                                   [:= :certnames.certname :reports.certname]
                                   [:= :certnames.latest_report_id :reports.id]]]}

               :alias "latest_report"
               :subquery? false
               :source-table "latest_report"}))

(def latest-report-id-query
  "Usually used as a subquery of reports"
  (map->Query {:projections {"latest_report_id" {:type :numeric
                                                 :queryable? true
                                                 :field :certnames.latest_report_id}}
               :selection {:from [:certnames]}
               :alias "latest_report_id"
               :subquery? false
               :source-table "latest_report_id"}))

(def environments-query
  "Basic environments query, more useful when used with subqueries"
  (map->Query {:projections {"name" {:type :string
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
               :source-table "environments"}))

(def producers-query
  "Basic producers query, more useful when used with subqueries"
  (map->Query {:projections {"name" {:type :string
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
              :source-table "producers"}))

(def packages-query
  "Basic packages query"
  (map->Query {:projections {"package_name" {:type :string
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
               :source-table "packages"}))

(def package-inventory-query
  "Packages and the machines they are installed on"
  (map->Query {:projections {"certname" {:type :string
                                         :queryable? true
                                         :field :certnames.certname}
                             "package_name" {:type :string
                                             :queryable? true
                                             :field :p.name}
                             "version" {:type :string
                                        :queryable? true
                                        :field :p.version}
                             "provider" {:type :string
                                         :queryable? true
                                         :field :p.provider}}

               :selection {:from [[:packages :p]]
                           :join [[:certname_packages :cp]
                                  [:= :cp.package_id :p.id]
                                  :certnames
                                  [:= :cp.certname_id :certnames.id]]}

               :relationships certname-relations

               :alias "package_inventory
               :subquery? false
               :source-table packages"}))

(def factsets-query
  "Query for the top level facts query"
  (map->Query
    {:projections
     {"timestamp" {:type :timestamp
                   :queryable? true
                   :field :timestamp}
      "facts" {:type :queryable-json
               :queryable? true
               :field {:select [(h/row-to-json :facts_data)]
                       :from [[{:select [[(hcore/raw "json_agg(json_build_object('name', t.name, 'value', t.value))")
                                          :data]
                                         [(hsql-hash-as-href "fs.certname" :factsets :facts)
                                          :href]]
                                :from [[{:select [[:key :name] :value :certname]
                                         :from [(hcore/raw "jsonb_each(fs.volatile || fs.stable)")]}
                                        :t]]}
                               :facts_data]]}}
      "certname" {:type :string
                  :queryable? true
                  :field :fs.certname}
      "hash" {:type :string
              :queryable? true
              :field (hsql-hash-as-str :fs.hash)}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :fs.producer_timestamp}
      "producer" {:type :string
                  :queryable? true
                  :field :producers.name}
      "environment" {:type :string
                     :queryable? true
                     :field :environments.environment}}

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
     :source-table "factsets"
     :subquery? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion from plan to SQL

(defn queryable-fields
  "Returns a list of queryable fields from a query record.

  These are fields with the setting :queryable? set to true."
  [{:keys [projections]}]
  (->> projections
       (filter (comp :queryable? val))
       keys
       sort))

(defn projectable-fields
  "Returns a list of projectable fields from a query record.

   Fields marked as :query-only? true are unable to be projected and thus are
   excluded."
  [{:keys [projections]}]
  (->> projections
       (remove (comp :query-only? val))
       keys))

(defn compile-fnexpression
  ([expr]
   (compile-fnexpression expr true))
  ([{:keys [function column params]} alias?]
   (let [honeysql-fncall (apply hcore/call function (cons column params))]
     (hcore/format (if alias?
                     [honeysql-fncall (get pg-fns->pdb-fns function)]
                     honeysql-fncall)))))

(defn wrap-with-inactive-nodes-cte
  "Wrap a selection in a CTE representing expired or deactivated certnames"
  [selection]
  (assoc selection :with {:inactive_nodes {:select [:certname]
                                           :from [:certnames]
                                           :where [:or
                                                   [:is-not :deactivated nil]
                                                   [:is-not :expired nil]]}}))

(defn honeysql-from-query
  "Convert a query to honeysql format"
  [{:keys [projected-fields group-by call selection projections entity subquery?]}]
  (let [fs (seq (map (comp hcore/raw :statement) call))
        select (if (and fs
                        (empty? projected-fields))
                 (vec fs)
                 (vec (concat (->> projections
                                   (remove (comp :query-only? second))
                                   (mapv (fn [[name {:keys [field]}]]
                                           [field name])))
                              fs)))
        new-selection (-> (cond-> selection (not subquery?) wrap-with-inactive-nodes-cte)
                          (assoc :select select)
                          (cond-> group-by (assoc :group-by group-by)))]
    (log/spy new-selection)))

(pls/defn-validated sql-from-query :- String
  "Convert a query to honeysql, then to sql"
  [query]
  (-> query
      honeysql-from-query
      hcore/format
      first
      log/spy))

(defprotocol SQLGen
  (-plan->sql [query] "Given the `query` plan node, convert it to a SQL string"))

(extend-protocol SQLGen
  Query
  (-plan->sql [{:keys [projections projected-fields where] :as query}]
    (s/validate [projection-schema] projected-fields)
    (let [has-where? (boolean where)
          has-projections? (not (empty? projected-fields))
          sql (-> query
                  (utils/update-cond has-where?
                                     [:selection]
                                     #(hsql/merge-where % (-plan->sql where)))
                  ;; Note that even if has-projections? is false, the
                  ;; projections are still relevant.
                  ;; i.e. projected-fields doesn't tell the
                  ;; whole story.  It's only relevant if it's not
                  ;; empty? and then it's decisive.
                  (utils/update-cond has-projections?
                                     [:projections]
                                     (constantly projected-fields))
                  sql-from-query)]

      (if (:subquery? query)
        (htypes/raw (str " ( " sql " ) "))
        sql)))

  InExpression
  (-plan->sql [{:keys [column subquery] :as this}]
    ;; 'column' is actually a vector of columns.
    (s/validate [column-schema] column)
    ;; if a field has type jsonb, cast that field in the subquery to jsonb
    (let [fields (mapv :field column)
          outer-types (mapv :type column)
          projected-fields (:projected-fields subquery)
          inner-columns (map first projected-fields)
          inner-types (map (comp :type second) projected-fields)
          coercions (mapv convert-type inner-columns inner-types outer-types)]
      [:in fields
       {:select coercions
        :from [[(-plan->sql subquery) :sub]]}]))

  JsonContainsExpression
  (-plan->sql [{:keys [field column-data array-in-path]}]
    (su/json-contains (if (instance? SqlRaw (:field column-data))
                        (-> column-data :field :s)
                        field)
                      array-in-path))

  FnBinaryExpression
  (-plan->sql [{:keys [value function args operator]}]
    (su/fn-binary-expression operator function args))

  JsonbPathBinaryExpression
  (-plan->sql [{:keys [field value column-data operator]}]
    (su/jsonb-path-binary-expression operator
                                     (if (instance? SqlRaw (:field column-data))
                                       (-> column-data :field :s)
                                       field)
                                     value))

  JsonbScalarRegexExpression
  (-plan->sql [{:keys [field]}]
    (su/jsonb-scalar-regex field))

  BinaryExpression
  (-plan->sql [{:keys [column operator value]}]
    (apply vector
           :or
           (map #(vector operator (-plan->sql %1) (-plan->sql %2))
                (cond
                  (map? column) [(:field column)]
                  (vector? column) (mapv :field column)
                  :else [column])
                (utils/vector-maybe value))))

  InArrayExpression
  (-plan->sql [{:keys [column]}]
    (s/validate column-schema column)
    (su/sql-in-array (:field column)))

  ArrayBinaryExpression
  (-plan->sql [{:keys [column]}]
    (s/validate column-schema column)
    (su/sql-array-query-string (:field column)))

  RegexExpression
  (-plan->sql [{:keys [column]}]
    (s/validate column-schema column)
    (su/sql-regexp-match (:field column)))

  ArrayRegexExpression
  (-plan->sql [{:keys [column]}]
    (s/validate column-schema column)
    (su/sql-regexp-array-match (:field column)))

  NullExpression
  (-plan->sql [{:keys [column] :as expr}]
    (s/validate column-schema column)
    (let [lhs (-plan->sql (:field column))
          json? (= :jsonb-scalar (:type column))]
      (if (:null? expr)
        (if json?
          (su/jsonb-null? lhs true)
          [:is lhs nil])
        (if json?
          (su/jsonb-null? lhs false)
          [:is-not lhs nil]))))

  AndExpression
  (-plan->sql [expr]
    (concat [:and] (map -plan->sql (:clauses expr))))

  OrExpression
  (-plan->sql [expr]
    (concat [:or] (map -plan->sql (:clauses expr))))

  NotExpression
  (-plan->sql [expr]
    [:not (-plan->sql (:clause expr))])

  Object
  (-plan->sql [obj]
    obj))

(defn plan->sql
  "Convert `query` to a SQL string"
  [query]
  (-plan->sql query))

(defn binary-expression?
  "True if the plan node is a binary expression"
  [node]
  (or (instance? BinaryExpression node)
      (instance? RegexExpression node)
      (instance? InArrayExpression node)
      (instance? ArrayBinaryExpression node)
      (instance? ArrayRegexExpression node)))

(defn path->nested-map
  "Given path [a b c] and value d, produce {a {b {c d}}}"
  [path value]
  (reduce #(hash-map (utils/maybe-strip-escaped-quotes %2) %1)
          (rseq (conj (vec path) value))))

(defn parse-dot-query
  "Transforms a dotted query into a JSON structure appropriate
   for comparison in the database."
  [{:keys [field value] :as node} state]
  (let [[column & path] (map utils/maybe-strip-escaped-quotes
                             (su/dotted-query->path field))]
    (if (some #(re-matches #"^\d+$" %) path)
      {:node (assoc node :value ["?" "?"] :field column :array-in-path true)
       :state (reduce conj state [(doto (PGobject.)
                                    (.setType "text[]")
                                    (.setValue (str "{" (string/join "," (map #(string/replace % "'" "''") path)) "}")))
                                  (su/munge-jsonb-for-storage value)])}
      {:node (assoc node :value "?" :field column :array-in-path false)
       :state (conj state (su/munge-jsonb-for-storage
                           (path->nested-map path value)))})))

(defn parse-dot-query-with-array-elements
  "Transforms a dotted query into a JSON structure appropriate
   for comparison in the database."
  [{:keys [field value] :as node} state]
  (let [[column & path] (->> field
                             su/dotted-query->path
                             (map utils/maybe-strip-escaped-quotes)
                             su/expand-array-access-in-path)
        qmarks (repeat (count path) "?")
        parameters (concat path [(su/munge-jsonb-for-storage value)
                                 (first path)])]
    {:node (assoc node :value qmarks :field column)
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

    ;; Handle a parameterized :selection -- the query's :selection
    ;; must already have question marks in all the right places, and
    ;; have the corresponding parameter values in :selection-params.
    ;; See rewrite-fact-query for an example.
    (and (instance? Query node)
         (-> node :selection :selection-params))
    {:node node
     :state (apply conj (-> node :selection :selection-params) state)}))

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

(def binary-operators
  #{"=" ">" "<" ">=" "<=" "~"})

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

(def non-predicate-clause
  #{"group_by" "limit" "offset"})

(defn expand-query-node
  "Expands/normalizes the user provided query to a minimal subset of the
  query language"
  [node]
  (cm/match [node]

            [[(op :guard #{"=" ">" "<" "<=" ">=" "~"}) (column :guard validate-dotted-field) value]]
            ;; (= :inventory (get-in (meta node) [:query-context :entity]))
            (when (string/includes? column "match(")
              (let [[head & path] (->> column
                                       utils/parse-matchfields
                                       su/dotted-query->path
                                       (map utils/maybe-strip-escaped-quotes))
                    path (if (= head "trusted") (cons head path) path)
                    fact_paths (->> (jdbc/query-to-vec "SELECT path_array FROM fact_paths WHERE (path ~ ? AND path IS NOT NULL)"
                                                       (doto (PGobject.)
                                                         (.setType "text")
                                                         (.setValue (string/join "#~" (utils/split-indexing path)))))
                                    (map :path_array)
                                    (map (fn [path] (if (= (first path) "trusted") path (cons "facts" path))))
                                    (map #(string/join "." %)))]
                (into ["or"] (map #(vector op % value) fact_paths))))

            [["extract" (columns :guard numeric-fact-functions?) (expr :guard no-type-restriction?)]]
            (when (= :facts (get-in meta node [:query-context :entity]))
              ["extract" columns ["and" ["=" ["function" "jsonb_typeof" "value"] "number"] expr]])

            [[(op :guard #{"=" "<" ">" "<=" ">="}) "value" (value :guard #(number? %))]]
            ["and" ["=" ["function" "jsonb_typeof" "value"] "number"] [op "value" value]]

            [["=" "value" (value :guard #(string? %))]]
            ["and" ["=" ["function" "jsonb_typeof" "value"] "string"] ["=" "value" value]]

            [["=" "value" (value :guard #(ks/boolean? %))]]
            ["and" ["=" ["function" "jsonb_typeof" "value"] "boolean"] ["=" "value" value]]

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
                                ["select_inactive_nodes"]]]]
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
                (throw (IllegalArgumentException.
                         (tru "All values in 'array' must be the same type.")))
                ["in" "certname"
                 ["extract" "certname"
                  ["select_facts"
                   ["and"
                    ["=" "name" fact-name]
                    ["in" "value" ["array" fact-values]]]]]])

              [[(op :guard #{"=" ">" "<" "<=" ">="}) ["fact" fact-name] fact-value]]
              (if-not (number? fact-value)
                (throw (IllegalArgumentException.
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
                    (throw (IllegalArgumentException.
                             (tru "Column definition for entity relationship ''{0}'' not valid" sub-entity))))
                  (do
                    ["in" (or local-columns columns)
                     ["extract" (or foreign-columns columns)
                      [(str "select_" sub-entity) expr]]]))
                (throw (IllegalArgumentException. (tru "No implicit relationship for entity ''{0}''" sub-entity)))))

            [["=" "latest_report?" value]]
            (let [entity (get-in (meta node) [:query-context :entity])
                  expanded-latest (case entity
                                    :reports
                                    ["in" "hash"
                                     ["extract" "latest_report_hash"
                                      ["select_latest_report"]]]

                                    :events
                                    ["in" "report_id"
                                     ["extract" "latest_report_id"
                                      ["select_latest_report_id"]]]

                                    (throw (IllegalArgumentException.
                                            (tru "Field 'latest_report?' not supported on endpoint ''{0}''" entity))))]
              (if value
                expanded-latest
                ["not" expanded-latest]))

            [[op (field :guard #{"new_value" "old_value"}) value]]
            [op field (su/db-serialize value)]

            [["=" field nil]]
            ["null?" (utils/dashes->underscores field) true]

            [[op "tag" array-value]]
            [op "tags" (str/lower-case array-value)]

            :else nil))

(def binary-operator-checker
  "A function that will return nil if the query snippet successfully validates, otherwise
  will return a data structure with error information"
  (s/checker [(s/one
               (apply s/enum binary-operators)
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

(defn remove-elided-nodes
  "This step removes elided nodes (marked with ::elide by expand-user-query) from
  `and` and `or` clauses."
  [node]
  (cm/match [node]
            [[& clauses]]
            (into [] (remove #(= ::elide %) clauses))

            :else nil))

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (let [query-context (:query-context (meta node))]
    (cm/match [node]

              [["=" field value]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when (and (or (= :numeric col-type)
                               (= :numeric col-type)) (string? value))
                  (throw
                   (IllegalArgumentException.
                    (tru
                     "Argument \"{0}\" is incompatible with numeric field \"{1}\"."
                     value (name field))))))

              [[(:or ">" ">=" "<" "<=")  (field :guard #(not (str/includes? %  "."))) _]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when-not (or (vec? field)
                              (contains? #{:numeric :timestamp :jsonb-scalar}
                                         col-type))
                  (throw (IllegalArgumentException. (tru "Query operators >,>=,<,<= are not allowed on field {0}" field)))))

              [["~>" field _]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when-not (contains? #{:path} col-type)
                  (throw (IllegalArgumentException. (tru "Query operator ~> is not allowed on field {0}" field)))))

              ;;This validation check is added to fix a failing facts
              ;;test. The facts test is checking that you can't submit
              ;;an empty query, but a ["=" ["node" "active"] true]
              ;;clause is automatically added, causing the empty query
              ;;to fall through to the and clause. Adding this here to
              ;;pass the test, but better validation for all clauses
              ;;needs to be added
              [["and" & clauses]]
              (when (some (fn [clause] (and (not= ::elide clause) (empty? clause))) clauses)
                (throw (IllegalArgumentException. (tru "[] is not well-formed: queries must contain at least one operator"))))

              ;;Facts is doing validation against nots only having 1
              ;;clause, adding this here to fix that test, need to make
              ;;another pass once other validations are known
              [["not" & clauses]]
              (when (not= 1 (count clauses))
                (throw (IllegalArgumentException. (tru "''not'' takes exactly one argument, but {0} were supplied" (count clauses)))))

              [[op & _]]
              (when (and (contains? binary-operators op)
                         (binary-operator-checker node))
                (throw (IllegalArgumentException. (tru "{0} requires exactly two arguments" op))))

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

(defn create-extract-node*
  "Returns a `query-rec` that has the correct projection for the given
   `column-list`. Updating :projected-fields causes the select in the SQL query
   to be modified."
  [{:keys [projections] :as query-rec} column-list expr]
  (let [names->fields (fn [names projections]
                        (mapv #(vector % (projections %))
                              names))]
    (if (or (nil? expr)
            (not (subquery-expression? expr)))
      (assoc query-rec
             :where (user-node->plan-node query-rec expr)
             :projected-fields (names->fields column-list projections))
      (let [[subname & subexpr] expr
            logobj (user-query->logical-obj subname)
            projections (:projections logobj)]
        (assoc logobj
               :projected-fields (names->fields column-list projections)
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
    (cond
      (> (count candidates) 1)
      (throw (IllegalArgumentException.
              (tru "Multiple ''{0}'' clauses are not permitted" clause)))

      (not (second (first candidates)))
      (throw (IllegalArgumentException.
              (tru "Received ''{0}'' clause without an argument" clause))))

      :else (second (first candidates))))

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
    offset (assoc-in [:selection :offset] offset)
    limit (assoc-in [:selection :limit] limit)
    order-by (assoc-in [:selection :order-by] order-by)))

(pls/defn-validated create-from-node
  :- {(s/optional-key :projected-fields) [projection-schema]
      s/Any s/Any}
  "Create an explicit subquery declaration to mimic the select_<entity>
   syntax."
  [entity expr clauses]
  (let [query-rec (user-query->logical-obj (str "select_" (utils/dashes->underscores entity)))
        {:keys [limit offset order-by]} (create-paging-map clauses)]
    (if (extract-expression? expr)
      (let [[extract columns remaining-expr] expr
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
  (let [[column params] (if (seq args)
                          [(first args) (rest args)]
                          ["*" []])
        qmarks (repeat (count params) "?")
        fnmap {:function (pdb-fns->pg-fns f)
               :column column
               :params (vec params)
               :args (vec qmarks)}
        compiled-fn (first (compile-fnexpression fnmap))]
    (map->FnExpression (-> fnmap
                           (assoc :statement compiled-fn)))))

(defn alias-columns
  "Alias columns with their fully qualified names, and function expressions
   with the function name."
  [query-rec c]
  (or (get-in query-rec [:projections c :field]) (keyword c)))

(pls/defn-validated columns->fields
  "Convert a list of columns to their true SQL field names."
  [query-rec
   columns]
  (->> columns
       (map #(if (= "function" (first %)) (second %) %))
       (sort-by #(if (string? %) % (:column %)))
       (mapv (partial alias-columns query-rec))))

(pls/defn-validated create-extract-node
  :- {(s/optional-key :projected-fields) [projection-schema]
      s/Any s/Any}
  [query-rec column expr]
  (let [[fcols cols] (strip-function-calls column)
        coalesce-fact-values (fn [col]
                               (if (and (= "factsets" (:source-table query-rec))
                                        (= "value" col))
                                 (convert-type "value" :jsonb-scalar :numeric)
                                 (or (get-in query-rec [:projections col :field]) col)))]
    (if-let [calls (seq
                     (map (fn [[name & args]]
                            (apply vector
                                   name
                                   (if (empty? args)
                                     [:*]
                                     (map coalesce-fact-values args))))
                          fcols))]
      (-> query-rec
          (assoc :call (map create-fnexpression calls))
          (create-extract-node* cols expr))
      (create-extract-node* query-rec cols expr))))

(defn try-parse-timestamp
  "Try to convert a string to a timestamp, throwing an exception if it fails"
  [ts]
  (or (to-timestamp ts)
      (throw (IllegalArgumentException. (tru "''{0}'' is not a valid timestamp value" ts)))))

(defn user-node->plan-node
  "Create a query plan for `node` in the context of the given query (as `query-rec`)"
  [query-rec node]
  (cm/match [node]
            [[(op :guard #{"=" "~" ">" "<" "<=" ">="}) ["function" f & args] value]]
            (map->FnBinaryExpression {:operator op
                                      :function f
                                      :args args
                                      :value value})

            [["=" column-name value]]
            (let [colname (first (str/split column-name #"\."))
                  cinfo (get-in query-rec [:projections colname])]
              (case (:type cinfo)
               :timestamp
               (map->BinaryExpression {:operator :=
                                       :column cinfo
                                       :value (try-parse-timestamp value)})

               :array
               (map->ArrayBinaryExpression {:column cinfo
                                            :value value})

               :path
               (map->BinaryExpression {:operator :=
                                       :column cinfo
                                       :value (facts/factpath-to-string value)})

               :queryable-json
               (if (string/includes? column-name "[")
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
                (throw (IllegalArgumentException.
                        (tru "Operator 'array' requires a vector argument"))))
              (case (:type cinfo)
                :array
                (throw (IllegalArgumentException.
                        (tru "Operator 'in'...'array' is not supported on array types")))

                :timestamp
                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "timestamp"
                                                                   java.sql.Timestamp
                                                                   (map to-timestamp value))})
                :integer
                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "bigint"
                                                                   java.lang.Integer
                                                                   (map int value))})

                :path
                (map->InArrayExpression {:column cinfo
                                         :value (su/array-to-param "text"
                                                                   String
                                                                   (map facts/factpath-to-string value))})

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
            (let [colname (first (str/split column-name #"\."))
                  {:keys [type] :as cinfo} (get-in query-rec [:projections colname])]
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
                 (IllegalArgumentException.
                  (tru "Argument \"{0}\" and operator \"{1}\" have incompatible types."
                       value op)))))

            [["null?" column-name value]]
            (let [cinfo (get-in query-rec [:projections column-name])]
              (map->NullExpression {:column cinfo :null? value}))

            [["~" column-name value]]
            (let [colname (first (str/split column-name #"\."))
                  cinfo (get-in query-rec [:projections colname])]
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
                :path
                (map->RegexExpression {:column cinfo
                                       :value (facts/factpath-regexp-to-regexp
                                               value)})))

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
                (assoc :group-by (columns->fields query-rec clauses))
                (create-extract-node column nil))

            [["extract" column expr]]
            (create-extract-node query-rec column expr)

            [["extract" column expr ["group_by" & clauses]]]
            (-> query-rec
                (assoc :group-by (columns->fields query-rec clauses))
                (create-extract-node column expr))

            :else nil))

(defn convert-to-plan
  "Converts the given `user-query` to a query plan that can later be converted into
  a SQL statement"
  [query-rec paging-options user-query]
  (let [plan-node (user-node->plan-node query-rec user-query)
        projections (projectable-fields query-rec)]
    (if (instance? Query plan-node)
      plan-node
      (-> query-rec
          (assoc :where plan-node
                 :paging-options paging-options
                 :project-fields projections)))))

(declare push-down-context)

(defn unsupported-fields
  [field allowed-fields]
  (let [supported-calls (set (map #(vector "function" %) (keys pdb-fns->pg-fns)))]
    (remove #(or (contains? (set allowed-fields) %) (contains? supported-calls (take 2 %)))
            (ks/as-collection field))))

(defn validate-query-operation-fields
  "Checks if query operation contains allowed fields. Returns error
  message string if some of the fields are invalid.

  Error-action and error-context parameters help in formatting different error messages."
  [field allowed-fields query-name error-action error-context]
  (let [invalid-fields (unsupported-fields field allowed-fields)]
    (when (> (count invalid-fields) 0)
      (format "%s unknown '%s' %s %s%s. Acceptable fields are %s"
              error-action
              query-name
              (if (> (count invalid-fields) 1) "fields" "field")
              (utils/comma-separated-keywords invalid-fields)
              (if (empty? error-context) "" (str " " error-context))
              (utils/comma-separated-keywords allowed-fields)))))

(defn annotate-with-context
  "Add `context` as meta on each `node` that is a vector. This associates the
  the query context assocated to each query clause with it's associated context"
  [context]
  (fn [node state]
    (when (vec? node)
      (cm/match [node]
                [["from" entity query]]
                (let [query (push-down-context (user-query->logical-obj (str "select_" entity)) query)
                      nested-qc (:query-context (meta query))]
                  {:node (vary-meta ["from" entity query]
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

                [["extract" column [subquery-name :guard (complement #{"not" "group_by" "or" "and"}) _]]]
                (let [underscored-subquery-name (utils/dashes->underscores subquery-name)
                      error (if (contains? (set (keys user-name->query-rec-name)) underscored-subquery-name)
                              (tru "Unsupported subquery `{0}` - did you mean `{1}`?" subquery-name underscored-subquery-name)
                              (tru "Unsupported subquery `{0}`" subquery-name))]
                  {:node node
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
                {:node node
                 :state (conj
                          state
                          (if (empty? qfields)
                            (tru "''{0}'' is not a queryable object for {1}. Entity {2} has no queryable objects"
                                 field alias alias)
                            (tru "''{0}'' is not a queryable object for {1}. Known queryable objects are {2}"
                                 field alias (utils/comma-separated-keywords qfields))))}))

            ; This validation is only for top-level extract operator
            ; For in-extract operator validation, please see annotate-with-context function
            [["extract" field & _]]
            (let [query-context (:query-context (meta node))
                  extractable-fields (projectable-fields query-context)
                  column-validation-message (validate-query-operation-fields
                                              field
                                              extractable-fields
                                              (:alias query-context)
                                              "Can't extract" "")]
              (when column-validation-message
                {:node node
                 :state (conj state column-validation-message)}))

            [["in" field ["array" _]]]
            (let [{:keys [alias dotted-fields] :as query-context} (:query-context (meta node))
                  qfields (queryable-fields query-context)]
              (when-not (or (vec? field)
                            (contains? (set qfields) field)
                            (some #(re-matches % field) (map re-pattern dotted-fields)))
                {:node node
                 :state (conj state
                              (if (empty? qfields)
                                (tru "''{0}'' is not a queryable object for {1}. Entity {1} has no queryable objects"
                                     field alias)
                                (tru "''{0}'' is not a queryable object for {1}. Known queryable objects are {2}"
                                     field alias (utils/comma-separated-keywords qfields))))}))

            [["in" field & _]]
            (let [query-context (:query-context (meta node))
                  column-validation-message (validate-query-operation-fields
                                             field
                                             (queryable-fields query-context)
                                             (:alias query-context)
                                             "Can't match on" "for 'in'")]
              (when column-validation-message
                {:node node
                 :state (conj state column-validation-message)}))

            :else nil))

(defn valid-operator?
  [operator]
  (or (contains? #{"from" "in" "extract" "subquery" "and"
                   "or" "not" "function" "group_by" "null?"} operator)
      (contains? binary-operators operator)
      (contains? (ks/keyset user-name->query-rec-name) operator)))

(defn ops-to-lower
  "Lower cases operators (such as and/or)."
  [node state]
  (cm/match [node]
            [[(op :guard (comp valid-operator? str/lower-case)) & stmt-rest]]
            {:node (with-meta (vec (apply vector (str/lower-case op) stmt-rest))
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
                                              ops-to-lower])]
    (when (seq errors)
      (throw (IllegalArgumentException. (str/join \newline errors))))

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
                :from [[(hcore/raw (str "(select certname,"
                                        "        environment_id, "
                                        "        ?::text as key, "
                                        "        (stable||volatile)->? as value"
                                        " from factsets"
                                        " where (stable||volatile) ?? ?"
                                        ")"))
                        :fs]]
                :selection-params [fact-name fact-name fact-name])
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
  [version query warn]
  (cm/match
    query
    ["from" (entity-str :guard #(string? %)) & remaining-query]
    (let [remaining-query (cm/match
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
                                   (IllegalArgumentException.
                                    (str
                                     (tru "Your `from` query accepts an optional query only as a second argument.")
                                     " "
                                     (tru "Check your query and try again.")))))
          entity (keyword (utils/underscores->dashes entity-str))]
      (when warn
        (warn-experimental entity))
      {:remaining-query (:query remaining-query)
       :paging-clauses (:paging-clauses remaining-query)
       :entity entity})

    :else (throw (IllegalArgumentException.
                  (str
                   (trs "Your initial query must be of the form: [\"from\",<entity>,(<optional-query>)].")
                   " "
                   (trs "Check your query and try again."))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compile-user-query->sql
  "Given a user provided query and a Query instance, convert the
   user provided query to SQL and extract the parameters, to be used
   in a prepared statement"
  [query-rec user-query & [{:keys [include_total] :as paging-options}]]
  ;; Call the query-rec so we can evaluate query-rec functions
  ;; which depend on the db connection type
  (let [allowed-fields (map keyword (queryable-fields query-rec))
        paging-options (some->> paging-options
                                (paging/validate-order-by! allowed-fields)
                                (paging/dealias-order-by query-rec))
        [query-rec user-query] (rewrite-fact-query query-rec user-query)
        {:keys [plan params]} (->> user-query
                                   (push-down-context query-rec)
                                   expand-user-query
                                   optimize-user-query
                                   (convert-to-plan query-rec paging-options)
                                   extract-all-params)
        sql (plan->sql plan)
        paged-sql (jdbc/paged-sql sql paging-options)]
    (cond-> {:results-query (apply vector paged-sql params)}
      include_total (assoc :count-query (apply vector (jdbc/count-sql sql) params)))))
