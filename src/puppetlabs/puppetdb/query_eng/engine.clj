(ns puppetlabs.puppetdb.query-eng.engine
  (:require [clojure.core.match :as cm]
            [clojure.string :as string]
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
            [schema.core :as s])
  (:import [honeysql.types SqlCall SqlRaw]))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defrecord Query [projections selection source-table alias where
                  subquery? entity call group-by])
(defrecord BinaryExpression [operator column value])
(defrecord RegexExpression [column value])
(defrecord ArrayRegexExpression [table alias column value])
(defrecord NullExpression [column null?])
(defrecord ArrayBinaryExpression [column value])
(defrecord InExpression [column subquery])
(defrecord AndExpression [clauses])
(defrecord OrExpression [clauses])
(defrecord NotExpression [clause])

(def json-agg-row (comp h/json-agg h/row-to-json))
(def supported-fns #{"sum" "avg" "min" "max" "count"})
(defn jsonb-type [] (if (su/postgres?) :jsonb :text))

(defn hsql-hash-as-str
  [column-keyword]
  (->> column-keyword
       name
       su/sql-hash-as-str
       hcore/raw))

(defn hsql-uuid-as-str
  [column-keyword]
  (->> column-keyword
       name
       su/sql-uuid-as-str
       hcore/raw))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queryable Entities

(defn augment-for-subquery
  [{:keys [entity] :as query-rec}]
  (cond-> query-rec
    (= :facts entity) (assoc-in [:projections "value" :field]
                        (h/coalesce :fv.value_string
                                    (h/scast :fv.value_boolean :text)))))

(defn nodes-query
  "Query for nodes entities, mostly used currently for subqueries"
  []
  (map->Query {:projections {"certname" {:type :string
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
                             "latest_report_status" {:type :string
                                                     :queryable? true
                                                     :field :report_statuses.status}
                             "catalog_environment" {:type :string
                                                    :queryable? true
                                                    :field :catalog_environment.environment}
                             "report_environment" {:type :string
                                                   :queryable? true
                                                   :field :reports_environment.environment}}

               :selection {:from [:certnames]
                           :left-join [:catalogs
                                       [:= :certnames.certname :catalogs.certname]

                                       [:factsets :fs]
                                       [:= :certnames.certname :fs.certname]

                                       :reports
                                       [:and
                                        [:= :certnames.certname :reports.certname]
                                        [:in :reports.id {:select [:latest_report_id]
                                                          :from [:certnames]}]]

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
               :subquery? false}))

(defn resource-params-query
  "Query for the resource-params query, mostly used as a subquery"
  []
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

(defn fact-paths-query
  "Query for the resource-params query, mostly used as a subquery"
  []
  (map->Query {:projections {"type" {:type :string
                                     :queryable? true
                                     :field :type}
                             "path" {:type :path
                                     :queryable? true
                                     :field :path}}
               :selection {:from [[:fact_paths :fp]]
                           :join [[:facts :f]
                                  [:= :f.fact_path_id :fp.id]

                                  [:fact_values :fv]
                                  [:= :f.fact_value_id :fv.id]

                                  [:value_types :vt]
                                  [:= :fv.value_type_id :vt.id]]
                           :modifiers [:distinct]
                           :where [:!= :fv.value_type_id 5]}

               :source-table "fact_paths"
               :alias "fact_paths"
               :subquery? false}))

(defn fact-names-query
  []
  (map->Query {:projections {"name" {:type :string
                                     :queryable? true
                                     :field :name}}
               :selection {:from [[:fact_paths :fp]]
                           :modifiers [:distinct]}
               :source-table "fact_paths"
               :alias "fact_names"
               :subquery? false}))

(defn facts-query
  "Query structured facts."
  []
  (map->Query {:projections {"path" {:type :string
                                     :queryable? false
                                     :query-only? true
                                     :field :fp.path}

                             "value" {:type :multi
                                      :queryable? true
                                      :field :fv.value}
                             "depth" {:type :integer
                                      :queryable? false
                                      :query-only? true
                                      :field :fp.depth}
                             "certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.environment}
                             "value_integer" {:type :number
                                              :query-only? true
                                              :queryable? false
                                              :field :fv.value_integer}
                             "value_float" {:type :number
                                            :query-only? true
                                            :queryable? false
                                            :field :fv.value_float}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fp.name}
                             "type" {:type  :string
                                     :query-only? true
                                     :queryable? false
                                     :field :vt.type}}

               :selection {:from [[:factsets :fs]]
                           :join [[:facts :f]
                                  [:= :fs.id :f.factset_id]

                                  [:fact_values :fv]
                                  [:= :f.fact_value_id :fv.id]

                                  [:fact_paths :fp]
                                  [:= :f.fact_path_id :fp.id]

                                  [:value_types :vt]
                                  [:= :vt.id :fv.value_type_id]]
                           :left-join [[:environments :env]
                                       [:= :fs.environment_id :env.id]]
                           :where [:= :fp.depth 0]}

               :alias "facts"
               :source-table "facts"
               :entity :facts
               :subquery? false}))

(defn fact-contents-query
  "Query for fact nodes"
  []
  (map->Query {:projections {"path" {:type :path
                                     :queryable? true
                                     :field :fp.path}
                             "value" {:type :multi
                                      :queryable? true
                                      :field :fv.value}
                             "certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fp.name}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.environment}
                             "value_integer" {:type :number
                                              :queryable? false
                                              :field :fv.value_integer
                                              :query-only? true}
                             "value_float" {:type :number
                                            :queryable? false
                                            :field :fv.value_float
                                            :query-only? true}
                             "type" {:type :string
                                     :queryable? false
                                     :field :vt.type
                                     :query-only? true}}

               :selection {:from [[:factsets :fs]]
                           :join [[:facts :f]
                                  [:= :fs.id :f.factset_id]

                                  [:fact_values :fv]
                                  [:= :f.fact_value_id :fv.id]

                                  [:fact_paths :fp]
                                  [:= :f.fact_path_id :fp.id]

                                  [:value_types :vt]
                                  [:= :fv.value_type_id :vt.id]]
                           :left-join [[:environments :env]
                                       [:= :fs.environment_id :env.id]]
                           :where [:!= :fv.value_type_id 5]}

               :alias "fact_nodes"
               :source-table "facts"
               :subquery? false}))

(defn report-logs-query
  "Query intended to be used by the `/reports/<hash>/logs` endpoint
  used for digging into the logs for a specific report."
  []
  (map->Query {:projections {"logs" {:type :json
                                     :queryable? false
                                     :field (h/coalesce :logs
                                                        (h/scast :logs_json
                                                                 (jsonb-type)))}
                             "hash" {:type :string
                                     :queryable? true
                                     :query-only? true
                                     :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:reports]}

               :alias "logs"
               :subquery? false
               :entity :reports
               :source-table "reports"}))

(defn report-metrics-query
  "Query intended to be used by the `/reports/<hash>/metrics` endpoint
  used for digging into the metrics for a specific report."
  []
  (map->Query {:projections {"metrics" {:type :json
                                        :queryable? false
                                        :field (h/coalesce :reports.metrics
                                                           (h/scast
                                                             :reports.metrics_json
                                                             (jsonb-type)))}
                             "hash" {:type :string
                                     :queryable? true
                                     :query-only? true
                                     :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:reports]}

               :alias "metrics"
               :subquery? false
               :entity :reports
               :source-table "reports"}))

(defn reports-query
  "Query for the reports entity"
  []
  (map->Query
    {:projections
     {"hash"            {:type :string
                         :queryable? true
                         :field (hsql-hash-as-str :reports.hash)}
      "certname"        {:type :string
                         :queryable? true
                         :field :reports.certname}
      "puppet_version"  {:type :string
                         :queryable? true
                         :field :reports.puppet_version}
      "report_format"   {:type :number
                         :queryable? true
                         :field :reports.report_format}
      "configuration_version" {:type :string
                               :queryable? true
                               :field :reports.configuration_version}
      "start_time"      {:type :timestamp
                         :queryable? true
                         :field :reports.start_time}
      "end_time"        {:type :timestamp
                         :queryable? true
                         :field :reports.end_time}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :reports.producer_timestamp}
      "metrics" {:type :json
                 :queryable? false
                 :field {:select [(h/row-to-json :t)]
                         :from [[{:select
                                  [[(h/coalesce :metrics
                                                (h/scast :metrics_json (jsonb-type))) :data]
                                           [(hsql-hash-as-str :hash) :href]]} :t]]}
                 :expandable? true}
      "logs" {:type :json
              :queryable? false
              :field {:select [(h/row-to-json :t)]
                      :from [[{:select [[(h/coalesce :logs
                                                     (h/scast :logs_json (jsonb-type)))
                                         :data] [(hsql-hash-as-str :hash) :href]]} :t]]}
              :expandable? true}
      "receive_time"    {:type :timestamp
                         :queryable? true
                         :field :reports.receive_time}
      "transaction_uuid" {:type :string
                          :queryable? true
                          :field (hsql-uuid-as-str :reports.transaction_uuid)}
      "noop"            {:type :boolean
                         :queryable? true
                         :field :reports.noop}
      "environment"     {:type :string
                         :queryable? true
                         :field :environments.environment}
      "status"          {:type :string
                         :queryable? true
                         :field :report_statuses.status}
      "latest_report?"   {:type :string
                          :queryable? true
                          :query-only? true}
      "resource_events" {:type :json
                         :queryable? false
                         :expandable? true
                         :field {:select [(h/row-to-json :event_data)]
                                 :from [[{:select [[(json-agg-row :t) :data]
                                                   [(hsql-hash-as-str :hash) :href]]
                                          :from [[{:select [:re.status
                                                            :re.timestamp
                                                            :re.resource_type :re.resource_title :re.property
                                                            :re.new_value :re.old_value :re.message
                                                            :re.file :re.line :re.containment_path :re.containing_class]
                                                   :from [[:resource_events :re]]
                                                   :where [:= :reports.id :re.report_id]} :t]]}
                                         :event_data]]}}}
     :selection {:from [:reports]
                 :left-join [:environments
                             [:= :environments.id :reports.environment_id]

                             :report_statuses
                             [:= :reports.status_id :report_statuses.id]]}

     :alias "reports"
     :subquery? false
     :entity :reports
     :source-table "reports"}))

(defn catalog-query
  "Query for the top level catalogs entity"
  []
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
      "environment" {:type :string
                     :queryable? true
                     :field :e.environment}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :c.producer_timestamp}
      "resources" {:type :json
                   :queryable? false
                   :expandable? true
                   :field {:select [(h/row-to-json :resource_data)]
                           :from [[{:select [[(json-agg-row :t) :data]
                                             [:c.certname :href]]
                                    :from [[{:select [[(hsql-hash-as-str :cr.resource) :resource]
                                                      :cr.type :cr.title :cr.tags :cr.exported :cr.file :cr.line
                                                      [(keyword "rpc.parameters::json") :parameters]]
                                             :from [[:catalog_resources :cr]]
                                             :join [[:resource_params_cache :rpc]
                                                    [:= :rpc.resource :cr.resource]]
                                             :where [:= :cr.catalog_id :c.id]}
                                            :t]]}
                                   :resource_data]]}}
      "edges" {:type :json
               :queryable? false
               :expandable? true
               :field {:select [(h/row-to-json :edge_data)]
                       :from [[{:select [[(json-agg-row :t) :data]
                                         [:c.certname :href]]
                                :from [[{:select [[:sources.type :source_type] [:sources.title :source_title]
                                                  [:targets.type :target_type] [:targets.title :target_title]
                                                  [:edges.type :relationship]]
                                         :from [:edges]
                                         :join [[:catalog_resources :sources]
                                                [:and
                                                 [:= :edges.source :sources.resource]
                                                 [:= :sources.catalog_id :c.id]]

                                                [:catalog_resources :targets]
                                                [:and
                                                 [:= :edges.target :targets.resource]
                                                 [:= :targets.catalog_id :c.id]]]
                                         :where [:= :edges.certname :c.certname]}
                                        :t]]}
                               :edge_data]]}}}

     :selection {:from [[:catalogs :c]]
                 :left-join [[:environments :e]
                             [:= :c.environment_id :e.id]]}

     :alias "catalogs"
     :entity :catalogs
     :subquery? false
     :source-table "catalogs"}))

(defn edges-query
  "Query for catalog edges"
  []
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
                           :join [:catalogs
                                  [:= :catalogs.certname :edges.certname]

                                  [:catalog_resources :sources]
                                  [:and
                                   [:= :edges.source :sources.resource]
                                   [:= :catalogs.id :sources.catalog_id]]

                                  [:catalog_resources :targets]
                                  [:and
                                   [:= :edges.target :targets.resource]
                                   [:= :catalogs.id :targets.catalog_id]]]}

               :alias "edges"
               :subquery? false
               :source-table "edges"}))

(defn resources-query
  "Query for the top level resource entity"
  []
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
                             "line" {:type :number
                                     :queryable? true
                                     :field :line}
                             "parameters" {:type :string
                                           :queryable? true
                                           :field :rpc.parameters}}

               :selection {:from [[:catalog_resources :resources]]
                           :join [[:catalogs :c]
                                  [:= :resources.catalog_id :c.id]]
                           :left-join [[:environments :e]
                                       [:= :c.environment_id :e.id]

                                       [:resource_params_cache :rpc]
                                       [:= :rpc.resource :resources.resource]]}

               :alias "resources"
               :subquery? false
               :source-table "catalog_resources"}))

(defn report-events-query
  "Query for the top level reports entity"
  []
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
                             "line" {:type :number
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
                                               :query-only? true}}
               :selection {:from [[:resource_events :events]]
                           :join [:reports
                                  [:= :events.report_id :reports.id]]
                           :left-join [:environments
                                       [:= :reports.environment_id :environments.id]]}

               :alias "events"
               :subquery? false
               :entity :events
               :source-table "resource_events"}))

(defn latest-report-query
  "Usually used as a subquery of reports"
  []
  (map->Query {:projections {"latest_report_hash" {:type :string
                                                   :queryable? true
                                                   :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:certnames]
                           :join [:reports
                                  [:= :reports.id :certnames.latest_report_id]]}

               :alias "latest_report"
               :subquery? false
               :source-table "latest_report"}))

(defn environments-query
  "Basic environments query, more useful when used with subqueries"
  []
  (map->Query {:projections {"name" {:type :string
                                     :queryable? true
                                     :field :environment}}
               :selection {:from [:environments]}

               :alias "environments"
               :subquery? false
               :source-table "environments"}))

(defn factsets-query
  "Query for the top level facts query"
  []
  (map->Query
    {:projections
     {"timestamp" {:type :timestamp
                   :queryable? true
                   :field :timestamp}
      "facts" {:type :json
               :queryable? true
               :expandable? true
               :field {:select [(h/row-to-json :facts_data)]
                       :from [[{:select [[(json-agg-row :t) :data]
                                         [:factsets.certname :href]]
                                :from [[{:select [:fp.name :fv.value]
                                         :from [[:facts :f]]
                                         :join [[:fact_values :fv] [:= :fv.id :f.fact_value_id]
                                                [:fact_paths :fp] [:= :fp.id :f.fact_path_id]
                                                [:value_types :vt] [:= :vt.id :fv.value_type_id]]
                                         :where [:and [:= :depth 0] [:= :f.factset_id :factsets.id]]}
                                        :t]]}
                               :facts_data]]}}
      "certname" {:type :string
                  :queryable? true
                  :field :factsets.certname}
      "hash" {:type :string
              :queryable? true
              :field (hsql-hash-as-str :factsets.hash)}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :factsets.producer_timestamp}
      "environment" {:type :string
                     :queryable? true
                     :field :environments.environment}}

     :selection {:from [:factsets]
                 :left-join [:environments
                             [:= :factsets.environment_id :environments.id]]}

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
       keys
       sort))

(defn extract-fields
  "Return all fields from a projection, if expand? true. If expand? false,
   returns all fields except for expanded ones, which are returned as
   [<identifier> k], where identifier is the component used to make the href.

   Return nil for fields which are query-only? since these can't be projected
   either."
  [[name {:keys [expandable? field]}] entity expand?]
  (let [href-key (case entity
                       :catalogs :certname
                       :factsets :certname
                       :reports :hash
                       nil)]
    (if (or expand? (not expandable?))
      [field name]
      [href-key name])))

(defn merge-function-options
  "Optionally merge call and grouping into an existing query map.
   Alias function calls with escape-quoted function name for hsqldb compatability.
   For instance, (merge-function-options {} ['count' :*] ['status'])"
  [selection call grouping]
  (cond-> selection
    call (hsql/merge-select [(apply hcore/call call) (format "\"%s\"" (first call))])
    grouping (assoc :group-by (map keyword grouping))))

(defn honeysql-from-query
  "Convert a query to honeysql format"
  [{:keys [projected-fields group-by call selection projections entity]}]
  (let [expand? (su/postgres?)
        call (when-let [[f & args] (some-> call utils/vector-maybe)]
               (apply vector f (or (seq (map keyword args)) [:*])))
        new-select (if (and call (empty? projected-fields))
                     []
                     (->> (sort projections)
                          (remove (comp :query-only? val))
                          (mapv #(extract-fields % entity expand?))))]
    (-> selection
        (assoc :select new-select)
        (merge-function-options call group-by)
        log/spy)))

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
  (-plan->sql [query]
    (let [has-where? (boolean (:where query))
          has-projections? (not (empty? (:projected-fields query)))
          sql (-> query
                  (utils/update-cond has-where? [:selection] #(hsql/merge-where % (-plan->sql (:where query))))
                  (utils/update-cond has-projections? [:projections] #(select-keys % (:projected-fields query)))
                  sql-from-query)]

      (if (:subquery? query)
        (htypes/raw (str " ( " sql " ) "))
        sql)))

  InExpression
  (-plan->sql [expr]
    [:in (:column expr) (-plan->sql (:subquery expr))])

  BinaryExpression
  (-plan->sql [expr]
    (concat [:or] (map
                    #(vector (:operator expr)
                             (-plan->sql %1)
                             (-plan->sql %2))
                    (utils/vector-maybe (:column expr))
                    (utils/vector-maybe (:value expr)))))

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
    (let [lhs (-plan->sql (:column expr))]
      (if (:null? expr)
        [:is lhs nil]
        [:is-not lhs nil])))

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

(def user-name->query-rec-name
  {"select_facts" facts-query
   "select_fact_contents" fact-contents-query
   "select_nodes" nodes-query
   "select_latest_report" latest-report-query
   "select_params" resource-params-query
   "select_resources" resources-query})

(defn user-query->logical-obj
  "Keypairs of the stringified subquery keyword (found in user defined queries) to the
   appropriate plan node"
  [subquery]
  (augment-for-subquery (assoc ((get user-name->query-rec-name subquery)) :subquery? true)))

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
               (if value
                 ["and" ["null?" "deactivated" true]
                        ["null?" "expired" true]]
                 ["or" ["null?" "deactivated" false]
                       ["null?" "expired" false]])]]]

            [[(op :guard #{"=" "~"}) ["parameter" param-name] param-value]]
            ["in" "resource"
             ["extract" "res_param_resource"
              ["select_params"
               ["and"
                [op "res_param_name" param-name]
                [op "res_param_value" (su/db-serialize param-value)]]]]]

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
            (let [entity (get-in (meta node) [:query-context :entity])
                  expanded-latest (case entity
                                    :reports
                                    ["in" "hash"
                                     ["extract" "latest_report_hash"
                                      ["select_latest_report"]]]

                                    :events
                                    ["in" "report"
                                     ["extract" "latest_report_hash"
                                      ["select_latest_report"]]]

                                    (throw (IllegalArgumentException.
                                             (format "Field 'latest_report?' not supported on endpoint '%s'" entity))))]
              (if value
                expanded-latest
                ["not" expanded-latest]))

            [[op (field :guard #{"new_value" "old_value"}) value]]
            [op field (su/db-serialize value)]

            [["=" field nil]]
            ["null?" (utils/dashes->underscores field) true]

            [[op "tag" array-value]]
            [op "tags" (string/lower-case array-value)]

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

              [["=" field value]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when (and (= :number col-type) (string? value))
                  (throw
                    (IllegalArgumentException.
                      (format "Argument \"%s\" is incompatible with numeric field \"%s\"."
                              value (name field))))))

              [[(:or ">" ">=" "<" "<=") field _]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when-not (or (vec? field)
                              (contains? #{:number :timestamp :multi}
                                         col-type))
                  (throw (IllegalArgumentException. (format "Query operators >,>=,<,<= are not allowed on field %s" field)))))

              [["~>" field _]]
              (let [col-type (get-in query-context [:projections field :type])]
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
  (contains? (ks/keyset user-name->query-rec-name)
             (first expr)))

(defn create-extract-node
  "Returns a `query-rec` that has the correct projection for the given
   `column-list`. Updating :projected-fields causes the select in the SQL query
   to be modified."
  [query-rec column-list expr]
  (if (or (nil? expr)
          (not (subquery-expression? expr)))
    (assoc query-rec :where (user-node->plan-node query-rec expr)
      :projected-fields column-list)
    (let [[subquery-name & subquery-expression] expr]
      (assoc (user-query->logical-obj subquery-name)
        :projected-fields column-list
        :where (when (seq subquery-expression)
                 (user-node->plan-node (user-query->logical-obj subquery-name)
                                       (first subquery-expression)))))))

(pls/defn-validated columns->fields :- [(s/either s/Keyword SqlCall SqlRaw)]
  "Convert a list of columns to their true SQL field names."
  [query-rec
   columns :- [s/Str]]
  ; This case expression here could be eliminated if we just used a projections list
  ; and had the InExpression use that to generate the sql, but as it is the zipper we
  ; use to walk the plan won't see instances of hashes and uuids among fields that have
  ; gone through this function
  (map #(get-in query-rec [:projections % :field]) (sort columns)))

(defn strip-function-calls
  [column]
  (let [{functions true nonfunctions false} (group-by #(= "function" (first %)) column)]
    [(into [] (rest (first functions)))
     nonfunctions]))

(defn replace-numeric-args
  [fargs]
  (mapv #(string/replace % #"value" "COALESCE(value_integer,value_float)") fargs))

(defn user-node->plan-node
  "Create a query plan for `node` in the context of the given query (as `query-rec`)"
  [query-rec node]
  (cm/match [node]
            [["=" column value]]
            (let [{:keys [type field]} (get-in query-rec [:projections column])]
              (case type
               :timestamp
               (map->BinaryExpression {:operator :=
                                       :column field
                                       :value (to-timestamp value)})

               :array
               (map->ArrayBinaryExpression {:column field
                                            :value value})

               :number
               (map->BinaryExpression {:operator :=
                                       :column field
                                       :value value})

               :path
               (map->BinaryExpression {:operator :=
                                       :column field
                                       :value (facts/factpath-to-string value)})

               :multi
               (map->BinaryExpression {:operator :=
                                       :column (hsql-hash-as-str (keyword (str column "_hash")))
                                       :value (hash/generic-identity-hash value)})

               (map->BinaryExpression {:operator :=
                                       :column field
                                       :value value})))

            [[(op :guard #{">" "<" ">=" "<="}) column value]]
            (let [{:keys [type field]} (get-in query-rec [:projections column])]
              (if (or (= :timestamp type) (and (= :number type) (number? value)))
                (map->BinaryExpression {:operator (keyword op)
                                        :column field
                                        :value  (if (= :timestamp type)
                                                  (to-timestamp value)
                                                  value)})
                (throw
                  (IllegalArgumentException.
                    (format "Argument \"%s\" and operator \"%s\" have incompatible types."
                            value op)))))

            [["null?" column value]]
            (let [{:keys [field]} (get-in query-rec [:projections column])]
              (map->NullExpression {:column field
                                    :null? value}))

            [["~" column value]]
            (let [{:keys [type field]} (get-in query-rec [:projections column])]
              (case type
                :array
                (map->ArrayRegexExpression {:table (:source-table query-rec)
                                            :alias (:alias query-rec)
                                            :column field
                                            :value value})

                :multi
                (map->RegexExpression {:column (keyword (str column "_string"))
                                       :value value})

                (map->RegexExpression {:column field
                                       :value value})))

            [["~>" column value]]
            (let [{:keys [type field]} (get-in query-rec [:projections column])]
              (case type
                :path
                (map->RegexExpression {:column field
                                       :value (facts/factpath-regexp-to-regexp value)})))

            [["and" & expressions]]
            (map->AndExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["or" & expressions]]
            (map->OrExpression {:clauses (map #(user-node->plan-node query-rec %) expressions)})

            [["not" expression]]
            (map->NotExpression {:clause (user-node->plan-node query-rec expression)})

            [["in" column subquery-expression]]
            (map->InExpression {:column (columns->fields query-rec (utils/vector-maybe column))
                                :subquery (user-node->plan-node query-rec subquery-expression)})

            [["extract" [["function" & fargs]] expr]]
            (-> query-rec
                (assoc :call (replace-numeric-args fargs))
                (create-extract-node [] expr))

            [["extract" column expr]]
            (create-extract-node query-rec (utils/vector-maybe column) expr)

            [["extract" columns expr ["group_by" & clauses]]]
            (let [[fargs cols] (strip-function-calls columns)]
              (-> query-rec
                  (assoc :call (replace-numeric-args fargs))
                  (assoc :group-by clauses)
                  (create-extract-node cols expr)))

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
  (let [supported-calls (set (map #(vector "function" %) supported-fns))]
    (remove #(or (contains? (set allowed-fields) %) (contains? supported-calls (take 2 %)))
            (ks/as-collection field))))

(defn validate-query-operation-fields
  "Checks if query operation contains allowed fields. Returns error
  message string if some of the fields are invalid.

  Error-action and error-context parameters help in formatting different error messages."
  [field allowed-fields query-name error-action error-context]
  (let [invalid-fields (unsupported-fields field allowed-fields)]
    (when (> (count invalid-fields) 0)
      (format "%s unknown '%s' %s '%s'%s. Acceptable fields are: %s"
              error-action
              query-name
              (if (> (count invalid-fields) 1) "fields:" "field")
              (string/join "', '" invalid-fields)
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
            (let [{:keys [alias] :as query-context} (:query-context (meta node))
                  qfields (queryable-fields query-context)]
              (when-not (or (vec? field) (contains? (set qfields) field))
                {:node node
                 :state (conj state
                              (format "'%s' is not a queryable object for %s, %s" field alias
                                      (if (empty? qfields)
                                        (format "%s has no queryable objects" alias)
                                        (format "known queryable objects are %s" (json/generate-string qfields)))))}))

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

(defn dashes-to-underscores
  "Convert field names with dashes to underscores"
  [node state]
  (cm/match [node]
            [[(op :guard binary-operators) (field :guard string?) value]]
            {:node (with-meta [op (utils/dashes->underscores field) value]
                     (meta node))
             :state state}
            :else {:node node :state state}))

(pls/defn-validated op-to-string
  "Convert an operator to a lowercase string or vector of such."
  [op]
  (if (vector? op)
    (mapv string/lower-case op)
    (string/lower-case op)))

(defn ops-to-lower
  "Lower cases operators (such as and/or)."
  [node state]
  (cm/match [node]
            [[op & stmt-rest]]
            {:node (with-meta (vec (cons (op-to-string op) stmt-rest))
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
      (throw (IllegalArgumentException. (string/join \newline errors))))

    annotated-query))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compile-user-query->sql
  "Given a user provided query and a Query instance, convert the
   user provided query to SQL and extract the parameters, to be used
   in a prepared statement"
  [query-rec user-query & [{:keys [include_total] :as paging-options}]]
  ;; Call the query-rec so we can evaluate query-rec functions
  ;; which depend on the db connection type
  (let [query-rec (query-rec)
        allowed-fields (map keyword (queryable-fields query-rec))
        paging-options (some->> paging-options
                                (paging/validate-order-by! allowed-fields)
                                (paging/dealias-order-by query-rec))
        {:keys [plan params]} (->> user-query
                                   (push-down-context query-rec)
                                   expand-user-query
                                   (convert-to-plan query-rec paging-options)
                                   extract-all-params)
        sql (plan->sql plan)
        paged-sql (jdbc/paged-sql sql paging-options)]
    (cond-> {:results-query (apply vector paged-sql params)}
      include_total (assoc :count-query (apply vector (jdbc/count-sql sql) params)))))
