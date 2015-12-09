(ns puppetlabs.puppetdb.query-eng.engine
  (:require [clojure.core.match :as cm]
            [clojure.string :as str]
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
(defrecord InArrayExpression [table column value])
(defrecord RegexExpression [column value])
(defrecord ArrayRegexExpression [column value])
(defrecord NullExpression [column null?])
(defrecord ArrayBinaryExpression [column value])
(defrecord InExpression [column subquery])
(defrecord AndExpression [clauses])
(defrecord OrExpression [clauses])
(defrecord NotExpression [clause])

(def json-agg-row (comp h/json-agg h/row-to-json))
(def supported-fns #{"sum" "avg" "min" "max" "count"})

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

(defn augment-for-subquery
  [{:keys [entity] :as query-rec}]
  (cond-> query-rec
    (= :facts entity) (assoc-in [:projections "value" :field]
                                (h/coalesce :fv.value_string
                                            (h/scast :fv.value_boolean :text)))))

(def nodes-query
  "Query for nodes entities, mostly used currently for subqueries"
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

               :relationships {;; Children - direct
                               "factsets" {:columns ["certname"]}
                               "reports" {:columns ["certname"]}
                               "catalogs" {:columns ["certname"]}

                               ;; Children - transitive
                               "facts" {:columns ["certname"]}
                               "fact_contents" {:columns ["certname"]}
                               "events" {:columns ["certname"]}
                               "edges" {:columns ["certname"]}
                               "resources" {:columns ["certname"]}}

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
                             "value_integer" {:type :integer
                                              :query-only? true
                                              :queryable? false
                                              :field :fv.value_integer}
                             "value_float" {:type :float
                                            :query-only? true
                                            :queryable? false
                                            :field :fv.value_float}
                             "value_string" {:type :string
                                            :query-only? true
                                            :queryable? false
                                            :field :fv.value_string}
                             "value_boolean" {:type :boolean
                                              :query-only? true
                                              :queryable? false
                                              :field :fv.value_boolean}
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

               :relationships {;; Parents - direct
                               "factsets" {:columns ["certname"]}

                               ;; Parents - transitive
                               "nodes" {:columns ["certname"]}
                               "environments" {:local-columns ["environment"]
                                               :foreign-columns ["name"]}

                               ;; Children - direct
                               "fact_contents" {:columns ["certname" "name"]}}

               :alias "facts"
               :source-table "facts"
               :entity :facts
               :subquery? false}))

(def fact-contents-query
  "Query for fact nodes"
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
                             "value_integer" {:type :integer
                                              :queryable? false
                                              :field :fv.value_integer
                                              :query-only? true}
                             "value_float" {:type :float
                                            :queryable? false
                                            :field :fv.value_float
                                            :query-only? true}
                             "value_string" {:type :string
                                            :query-only? true
                                            :queryable? false
                                            :field :fv.value_string}
                             "value_boolean" {:type :boolean
                                              :query-only? true
                                              :queryable? false
                                              :field :fv.value_boolean}
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

               :relationships {;; Parents - direct
                               "facts" {:columns ["certname" "name"]}
                               "factsets" {:columns ["certname"]}

                               ;; Parents - transitive
                               "nodes" {:columns ["certname"]}
                               "environments" {:local-columns ["environment"]
                                               :foreign-columns ["name"]}}

               :alias "fact_nodes"
               :source-table "facts"
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
     {"hash"            {:type :string
                         :queryable? true
                         :field (hsql-hash-as-str :reports.hash)}
      "certname"        {:type :string
                         :queryable? true
                         :field :reports.certname}
      "puppet_version"  {:type :string
                         :queryable? true
                         :field :reports.puppet_version}
      "report_format"   {:type :integer
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
                                  [[(h/coalesce :metrics (h/scast :metrics_json :jsonb)) :data]
                                   [(hsql-hash-as-href (su/sql-hash-as-str "hash") :reports :metrics)
                                    :href]]} :t]]}
                 :expandable? true}
      "logs" {:type :json
              :queryable? false
              :field {:select [(h/row-to-json :t)]
                      :from [[{:select
                               [[(h/coalesce :logs (h/scast :logs_json :jsonb)) :data]
                                [(hsql-hash-as-href (su/sql-hash-as-str "hash") :reports :logs)
                                 :href]]} :t]]}
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
                                 :from [[{:select
                                          [[(json-agg-row :t) :data]
                                           [(hsql-hash-as-href (su/sql-hash-as-str "hash") :reports :events) :href]]
                                          :from [[{:select
                                                   [:re.status
                                                    :re.timestamp
                                                    :re.resource_type
                                                    :re.resource_title
                                                    :re.property
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

                             :report_statuses
                             [:= :reports.status_id :report_statuses.id]]}

     :relationships {;; Parents - direct
                     "nodes" {:columns ["certname"]}
                     "environments" {:local-columns ["environment"]
                                     :foreign-columns ["name"]}

                     ;; Children - direct
                     "events" {:local-columns ["hash"]
                               :foreign-columns ["report"]}}

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
      "code_id" {:type :string
                 :queryable? true
                 :field :c.code_id}
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
                                             [(hsql-hash-as-href "c.certname" :catalogs :resources) :href]]
                                    :from [[{:select [[(hsql-hash-as-str :cr.resource) :resource]
                                                      :cr.type :cr.title :cr.tags :cr.exported :cr.file :cr.line
                                                      [(h/scast :rpc.parameters :json) :parameters]]
                                             :from [[:catalog_resources :cr]]
                                             :join [[:resource_params_cache :rpc]
                                                    [:= :rpc.resource :cr.resource]]
                                             :where [:= :cr.certname_id :latest_catalogs.certname_id]}
                                            :t]]}
                                   :resource_data]]}}
      "edges" {:type :json
               :queryable? false
               :expandable? true
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
                                                 [:= :sources.certname_id :latest_catalogs.certname_id]]

                                                [:catalog_resources :targets]
                                                [:and
                                                 [:= :edges.target :targets.resource]
                                                 [:= :targets.certname_id :latest_catalogs.certname_id]]]
                                         :where [:= :edges.certname :c.certname]}
                                        :t]]}
                               :edge_data]]}}}

     :selection {:from [:latest_catalogs]
                 :join [[:catalogs :c]
                        [:= :latest_catalogs.catalog_id :c.id]]
                 :left-join [[:environments :e]
                             [:= :c.environment_id :e.id]]}

     :relationships {;; Parents - direct
                     "node" {:columns ["certname"]}
                     "environments" {:local-columns ["environment"]
                                     :foreign-columns ["name"]}

                     ;; Children - direct
                     "edges" {:columns ["certname"]}
                     "resources" {:columns ["certname"]}}

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

               :relationships {;; Parents - direct
                               "catalogs" {:columns ["certname"]}

                               ;; Parents - transitive
                               "nodes" {:columns ["certname"]}}

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
                             "line" {:type :integer
                                     :queryable? true
                                     :field :line}
                             "parameters" {:type :json
                                           :queryable? true
                                           :field (h/scast :rpc.parameters :json)}}

               :selection {:from [[:catalog_resources :resources]]
                           :join [:latest_catalogs
                                  [:= :latest_catalogs.certname_id :resources.certname_id]
                                  [:catalogs :c]
                                  [:= :latest_catalogs.catalog_id :c.id]]
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
                             "line" {:type :integer
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

               :relationships {;; Parents - direct
                               "reports" {:local-columns ["report"]
                                          :foreign-columns ["hash"]}

                               ;; Parents - transitive
                               "nodes" {:columns ["certname"]}
                               "environments" {:local-columns ["environment"]
                                               :foreign-columns ["name"]}}

               :alias "events"
               :subquery? false
               :entity :events
               :source-table "resource_events"}))

(def latest-report-query
  "Usually used as a subquery of reports"
  (map->Query {:projections {"latest_report_hash" {:type :string
                                                   :queryable? true
                                                   :field (hsql-hash-as-str :reports.hash)}}
               :selection {:from [:certnames]
                           :join [:reports
                                  [:= :reports.id :certnames.latest_report_id]]}

               :alias "latest_report"
               :subquery? false
               :source-table "latest_report"}))

(def environments-query
  "Basic environments query, more useful when used with subqueries"
  (map->Query {:projections {"name" {:type :string
                                     :queryable? true
                                     :field :environment}}
               :selection {:from [:environments]}

               :relationships {;; Children - direct
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

(def factsets-query
  "Query for the top level facts query"
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
                                         [(hsql-hash-as-href "factsets.certname" :factsets :facts) :href]]
                                :from [[{:select [:fp.name (h/scast :fv.value :jsonb)]
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

     :relationships {;; Parents - direct
                     "nodes" {:columns ["certname"]}
                     "environments" {:local-columns ["environment"]
                                     :foreign-columns ["name"]}

                     ;; Children - direct
                     "facts" {:columns ["certname"]}
                     "fact_contents" {:columns ["certname"]}}

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
  "Returns all fields from a projection.  Returns nil for fields which
  are query-only? since these can't be projected either."
  [[name {:keys [field]}] entity]
  [field name])

(defn merge-function-options
  "Optionally merge call and grouping into an existing query map.
   For instance, (merge-function-options {} ['count' :*] ['status'])"
  [selection call grouping]
  (cond-> selection
    call (hsql/merge-select [(apply hcore/call call) (first call)])
    grouping (assoc :group-by (map keyword grouping))))

(defn honeysql-from-query
  "Convert a query to honeysql format"
  [{:keys [projected-fields group-by call selection projections entity]}]
  (let [call (when-let [[f & args] (some-> call utils/vector-maybe)]
               (apply vector f (or (seq (map keyword args)) [:*])))
        new-select (if (and call (empty? projected-fields))
                     []
                     (->> (sort projections)
                          (remove (comp :query-only? val))
                          (mapv #(extract-fields % entity))))]
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

  InArrayExpression
  (-plan->sql [expr]
    (su/sql-in-array (:column expr)))

  ArrayBinaryExpression
  (-plan->sql [expr]
    (su/sql-array-query-string (:column expr)))

  RegexExpression
  (-plan->sql [expr]
    (su/sql-regexp-match (:column expr)))

  ArrayRegexExpression
  (-plan->sql [expr]
    (su/sql-regexp-array-match (:column expr)))

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
      (instance? InArrayExpression node)
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
  {"select_catalogs" catalog-query
   "select_edges" edges-query
   "select_environments" environments-query
   "select_events" report-events-query
   "select_facts" facts-query
   "select_factsets" factsets-query
   "select_fact_contents" fact-contents-query
   "select_fact_paths" fact-paths-query
   "select_nodes" nodes-query
   "select_latest_report" latest-report-query
   "select_params" resource-params-query
   "select_reports" reports-query
   "select_resources" resources-query})

(defn user-query->logical-obj
  "Keypairs of the stringified subquery keyword (found in user defined queries) to the
   appropriate plan node"
  [subquery]
  (-> (get user-name->query-rec-name subquery)
      (assoc :subquery? true)
      augment-for-subquery))

(def binary-operators
  #{"=" ">" "<" ">=" "<=" "~"})

(defn expand-query-node
  "Expands/normalizes the user provided query to a minimal subset of the
  query language"
  [node]
  (cm/match [node]

            [[(op :guard #{"=" "<" ">" "<=" ">="}) "value" (value :guard #(number? %))]]
            ["or" [op "value_integer" value] [op "value_float" value]]

            [[(op :guard #{"="}) "value"
              (value :guard #(or (string? %) (ks/boolean? %)))]]
            (let [value-column (if (string? value) "value_string" "value_boolean")]
            [op value-column value])

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

            [[(op :guard #{"=" "~"}) ["fact" fact-name]
              (fact-value :guard #(or (string? %) (instance? Boolean %)))]]
            (let [value-column (if (string? fact-value) "value_string" "value_boolean")]
              ["in" "certname"
               ["extract" "certname"
                ["select_facts"
                 ["and"
                  ["=" "name" fact-name]
                  [op value-column fact-value]]]]])

            [["in" ["fact" fact-name] ["array" fact-values]]]
            (let [clause (cond
                           (every? string? fact-values)
                           ["in" "value_string" ["array" fact-values]]

                           (every? ks/boolean? fact-values)
                           ["in" "value_boolean" ["array" fact-values]]

                           (every? number? fact-values)
                           ["or"
                            ["in" "value_float" ["array" fact-values]]
                            ["in" "value_integer" ["array" fact-values]]]

                           :else (throw (IllegalArgumentException.
                                         "All values in 'array' must be the same type.")))]
              ["in" "certname"
               ["extract" "certname"
                ["select_facts"
                 ["and"
                  ["=" "name" fact-name]
                  clause]]]])


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

            [["subquery" sub-entity expr]]
            (let [relationships (get-in (meta node) [:query-context :relationships sub-entity])]
              (if relationships
                (let [{:keys [columns local-columns foreign-columns]} relationships]
                  (when-not (or columns (and local-columns foreign-columns))
                    (throw (IllegalArgumentException. (format "Column definition for entity relationship '%s' not valid" sub-entity))))
                  (do
                    (log/warn "The `subquery` operator is experimental and may change in the future.")
                    ["in" (or local-columns columns)
                     ["extract" (or foreign-columns columns)
                      [(str "select_" sub-entity) expr]]]))
                (throw (IllegalArgumentException. (format "No implicit relationship for entity '%s'" sub-entity)))))

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

(defn validate-binary-operators
  "Validation of the user provided query"
  [node]
  (let [query-context (:query-context (meta node))]
    (cm/match [node]

              [["=" field value]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when (and (or (= :integer col-type)
                               (= :float col-type)) (string? value))
                  (throw
                    (IllegalArgumentException.
                      (format "Argument \"%s\" is incompatible with numeric field \"%s\"."
                              value (name field))))))

              [[(:or ">" ">=" "<" "<=") field _]]
              (let [col-type (get-in query-context [:projections field :type])]
                (when-not (or (vec? field)
                              (contains? #{:float :integer :timestamp :multi}
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

(defn extract-expression?
  "Returns true if expr is an extract expression"
  [expr]
  (let [f (first expr)]
    (and (str f) (= f "extract"))))

(defn create-from-node
  "Create an explicit subquery declaration to mimic the select_<entity>
  syntax."
  [entity expr]
  (let [query-rec (user-query->logical-obj (str "select_" (utils/dashes->underscores entity)))]
    (if (extract-expression? expr)
      (let [[extract columns remaining-expr] expr
            column-list (utils/vector-maybe columns)]
        (assoc query-rec
          :projected-fields column-list
          :where (user-node->plan-node query-rec remaining-expr)))
      (assoc query-rec
        :where (user-node->plan-node query-rec expr)))))

(pls/defn-validated columns->fields :- [(s/cond-pre s/Keyword SqlCall SqlRaw)]
  "Convert a list of columns to their true SQL field names."
  [query-rec
   columns :- [s/Str]]
  ; This case expression here could be eliminated if we just used a projections list
  ; and had the InExpression use that to generate the sql, but as it is the zipper we
  ; use to walk the plan won't see instances of hashes and uuids among fields that have
  ; gone through this function
  (map #(get-in query-rec [:projections % :field]) (sort columns)))

(defn strip-function-calls
  [column-or-columns]
  (let [columns (utils/vector-maybe column-or-columns)
        {[function-call] true nonfunctions false} (group-by #(= "function" (first %)) columns)]
    [(vec (rest function-call))
     (vec nonfunctions)]))

(defn replace-numeric-args
  [fargs]
  (mapv #(str/replace % #"value" "COALESCE(value_integer,value_float)") fargs))

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

               :path
               (map->BinaryExpression {:operator :=
                                       :column field
                                       :value (facts/factpath-to-string value)})

               (map->BinaryExpression {:operator :=
                                       :column field
                                       :value value})))

            [["in" column ["array" value]]]
            (let [{:keys [type field]} (get-in query-rec [:projections column])]
              (when-not (coll? value)
                (throw (IllegalArgumentException. "Operator 'array' requires a vector argument")))
              (case type
                :array
                (throw (IllegalArgumentException. "Operator 'in'...'array' is not supported on array types"))

                :timestamp
                (map->InArrayExpression {:column field
                                         :value (su/array-to-param "timestamp"
                                                                   java.sql.Timestamp
                                                                   (map to-timestamp value))})

                :float
                (map->InArrayExpression {:column field
                                         :value (su/array-to-param "float4"
                                                                   java.lang.Double
                                                                   (map double value))})
                :integer
                (map->InArrayExpression {:column field
                                         :value (su/array-to-param "bigint"
                                                                   java.lang.Integer
                                                                   (map int value))})

                :path
                (map->InArrayExpression {:column field
                                         :value (su/array-to-param "text"
                                                                   String
                                                                   (map facts/factpath-to-string value))})

                (map->InArrayExpression {:column field
                                         :value (su/array-to-param "text"
                                                                   String
                                                                   (map str value))})))

            [[(op :guard #{">" "<" ">=" "<="}) column value]]
            (let [{:keys [type field]} (get-in query-rec [:projections column])]
              (if (or (= :timestamp type) (and (or (= :float type)
                                                   (= :integer type)) (number? value)))
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
                (map->ArrayRegexExpression {:column field
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

            ;; This provides the from capability to replace the select_<entity> syntax from an
            ;; explicit subquery.
            [["from" entity expr]]
            (create-from-node entity expr)

            [["extract" column]]
            (let [[fargs cols] (strip-function-calls column)
                  call (replace-numeric-args fargs)
                  query-rec-with-call (cond-> query-rec
                                        (not (empty? call)) (assoc :call call))]
              (create-extract-node query-rec-with-call cols nil))

            [["extract" columns ["group_by" & clauses]]]
            (let [[fargs cols] (strip-function-calls columns)]
              (-> query-rec
                  (assoc :call (replace-numeric-args fargs))
                  (assoc :group-by clauses)
                  (create-extract-node cols nil)))

            [["extract" column expr]]
            (let [[fargs cols] (strip-function-calls column)
                  call (replace-numeric-args fargs)
                  query-rec-with-call (cond-> query-rec
                                        (not (empty? call)) (assoc :call call))]
              (create-extract-node query-rec-with-call cols expr))

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
                [["from" entity query]]
                (let [query (push-down-context (user-query->logical-obj (str "select_" entity)) query)
                      nested-qc (:query-context (meta query))]
                  {:node (vary-meta ["from" entity
                                     (vary-meta query
                                                assoc :query-context nested-qc)]
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

                [["subquery" relationship expr]]
                (let [subquery-expr (push-down-context
                                     (user-query->logical-obj (str "select_" relationship))
                                     expr)
                      nested-qc (:query-context (meta subquery-expr))]

                  {:node (vary-meta ["subquery" relationship
                                     (vary-meta expr
                                                assoc :query-context nested-qc)]
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

            [["in" _ ["array" _]]]
            nil

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
                                              dashes-to-underscores ops-to-lower])]
    (when (seq errors)
      (throw (IllegalArgumentException. (str/join \newline errors))))

    annotated-query))

;; Top-level parsing

(def experimental-entities
  #{:event-counts :aggregate-event-counts})

(defn warn-experimental
  "Show a warning if the endpoint is experimental."
  [entity]
  (when (contains? experimental-entities entity)
    (log/warn (format
                "The %s entity is experimental and may be altered or removed in the future."
                (name entity)))))

(defn parse-query-context
  "Parses a top-level query with a 'from', validates it and returns the entity and remaining-query in
  a map."
  [version query warn]
  (cm/match
   query
   ["from" (entity-str :guard #(string? %)) & remaining-query]
   (let [remaining-query (cm/match
                          remaining-query
                          [(q :guard #(vector? %))] q
                          [] []
                          :else (throw (IllegalArgumentException. "Your `from` query accepts an optional query only as a second argument. Check your query and try again.")))
         entity (keyword (utils/underscores->dashes entity-str))]
     (when warn
       (warn-experimental entity))
     {:remaining-query remaining-query
      :entity entity})

   :else (throw (IllegalArgumentException. (format "Your initial query must be of the form: [\"from\",<entity>,(<optional-query>)]. Check your query and try again.")))))

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
        {:keys [plan params]} (->> user-query
                                   (push-down-context query-rec)
                                   expand-user-query
                                   (convert-to-plan query-rec paging-options)
                                   extract-all-params)
        sql (plan->sql plan)
        paged-sql (jdbc/paged-sql sql paging-options)]
    (cond-> {:results-query (apply vector paged-sql params)}
      include_total (assoc :count-query (apply vector (jdbc/count-sql sql) params)))))
