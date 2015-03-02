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
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.query.paging :as paging]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.honeysql :as h]
            [honeysql.helpers :as hsql]
            [honeysql.types :as htypes]
            [honeysql.core :as hcore]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Plan - functions/transformations of the internal query plan

(defrecord Query [projections selection source-table alias where
                  subquery? entity late-project?])
(defrecord BinaryExpression [operator column value])
(defrecord RegexExpression [column value])
(defrecord ArrayRegexExpression [table column value])
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
  (map->Query {:projections {"certname" {:type :string
                                         :queryable? true
                                         :field :certnames.certname}
                             "deactivated" {:type :string
                                            :queryable? true
                                            :field :certnames.deactivated}
                             "facts_environment" {:type :string
                                                  :queryable? true
                                                  :field :facts_environment.name}
                             "catalog_timestamp" {:type :timestamp
                                                  :queryable? true
                                                  :field :catalogs.timestamp}
                             "facts_timestamp" {:type :timestamp
                                                :queryable? true
                                                :field :fs.timestamp}
                             "report_timestamp" {:type :timestamp
                                                 :queryable? true
                                                 :field :reports.end_time}
                             "catalog_environment" {:type :string
                                                    :queryable? true
                                                    :field :catalog_environment.name}
                             "report_environment" {:type :string
                                                   :queryable? true
                                                   :field :reports_environment.name}}

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
                                                   :field :resource}
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
                                     :field [:distinct-on :type]}
                             "path" {:type :path
                                     :queryable? true
                                     :field :path}}
               :selection {:from [[:fact_paths :fp]]
                           :join [[:facts :f]
                                  [:= :f.fact_path_id :fp.id]

                                  [:fact_values :fv]
                                  [:= :f.fact_value_id :fv.id]

                                  [:value_types :vt]
                                  [:= :fp.value_type_id :vt.id]]
                           :where [:!= :fp.value_type_id 5]}

               :source-table "fact_paths"
               :alias "fact_paths"
               :subquery? false}))

(def facts-query
  "Query structured facts."
  (map->Query {:projections {"path" {:type :string
                                     :queryable? false
                                     :field :fp.path}
                             "value" {:type :multi
                                      :queryable? true
                                      :field (h/coalesce :fv.value_string
                                                         :fv.value_json
                                                         (h/scast :fv.value_boolean :text))}
                             "depth" {:type :integer
                                      :queryable? false
                                      :field :fp.depth}
                             "certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.name}
                             "value_integer" {:type :number
                                              :queryable? false
                                              :field :fv.value_integer}
                             "value_float" {:type :number
                                            :queryable? false
                                            :field :fv.value_float}
                             "value_hash" {:type :string
                                           :queryable? false
                                           :field :fv.value_hash}
                             "value_string" {:type :string
                                             :queryable? false
                                             :field :fv.value_string}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fp.name}
                             "type" {:type  :string
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
                           :where [:= :depth 0]}

               :alias "facts"
               :source-table "facts"
               :entity :facts
               :subquery? false
               :late-project? true}))

(def fact-contents-query
  "Query for fact nodes"
  (map->Query {:projections {"path" {:type :path
                                     :queryable? true
                                     :field :fp.path}
                             "value" {:type :multi
                                      :queryable? true
                                      :field (h/coalesce :fv.value_string
                                                         (h/scast :fv.value_boolean :text))}
                             "certname" {:type :string
                                         :queryable? true
                                         :field :fs.certname}
                             "name" {:type :string
                                     :queryable? true
                                     :field :fp.name}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :env.name}
                             "value_integer" {:type :number
                                              :queryable? false
                                              :field :fv.value_integer}
                             "value_float" {:type :number
                                            :queryable? false
                                            :field :fv.value_float}
                             "value_string" {:type :string
                                             :queryable? false
                                             :field :fv.value_string}
                             "value_hash" {:type :string
                                           :queryable? false
                                           :field :fv.value_hash}
                             "type" {:type :string
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
                                  [:= :fp.value_type_id :vt.id]]
                           :left-join [[:environments :env]
                                       [:= :fs.environment_id :env.id]]
                           :where [:!= :fp.value_type_id 5]}

               :alias "fact_nodes"
               :source-table "facts"
               :subquery? false
               :late-project? true}))

(def reports-query
  "Query for the reports entity"
  (map->Query {:projections {"hash"            {:type :string
                                                :queryable? true
                                                :field :reports.hash}
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
                             "metrics"        {:type :json
                                                :queryable? false
                                                :field :reports.metrics}
                             "logs"        {:type :json
                                                :queryable? false
                                                :field :reports.logs}
                             "receive_time"    {:type :timestamp
                                                :queryable? true
                                                :field :reports.receive_time}
                             "transaction_uuid" {:type :string
                                                 :queryable? true
                                                 :field :reports.transaction_uuid}
                             "noop"            {:type :boolean
                                                :queryable? true
                                                :field :reports.noop}
                             "environment"     {:type :string
                                                :queryable? true
                                                :field :environments.name}
                             "status"          {:type :string
                                                :queryable? true
                                                :field :report_statuses.status}
                             "latest_report?"   {:type :string
                                                 :queryable? true
                                                 :query-only? true}
                             "resource_events" {:type :json
                                                :queryable? false
                                                :expandable? true
                                                :field {:select [(h/json-agg
                                                                  (h/row-to-json
                                                                   (h/row
                                                                    :re.status :re.timestamp :re.resource_type :re.resource_title :re.property
                                                                    :re.new_value :re.old_value :re.message :re.file :re.line
                                                                    :re.containment_path :re.containing_class)))]
                                                        :from [[:resource_events :re]]
                                                        :where [:= :reports.id :re.report_id]}}}
               :selection {:from [:reports]
                           :left-join [:environments
                                       [:= :environments.id :reports.environment_id]

                                       :report_statuses
                                       [:= :reports.status_id :report_statuses.id]]}

               :alias "reports"
               :subquery? false
               :entity :reports
               :source-table "reports"}))

(def catalog-query
  "Query for the top level catalogs entity"
  (map->Query {:projections {"version" {:type :string
                                        :queryable? true
                                        :field :c.catalog_version}
                             "certname" {:type :string
                                     :queryable? true
                                     :field :c.certname}
                             "hash" {:type :string
                                     :queryable? true
                                     :field :c.hash}
                             "transaction_uuid" {:type :string
                                                 :queryable? true
                                                 :field :c.transaction_uuid}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :e.name}
                             "producer_timestamp" {:type :string
                                                   :queryable? true
                                                   :field :c.producer_timestamp}
                             "resources" {:type :json
                                          :queryable? false
                                          :expandable? true
                                          :field {:select [(h/json-agg
                                                            (h/row-to-json
                                                             (h/row
                                                              :cr.resource :cr.type :cr.title :cr.tags :cr.exported
                                                              :cr.file :cr.line :rpc.parameters)))]
                                                  :from [[:catalog_resources :cr]]
                                                  :join [[:resource_params_cache :rpc]
                                                         [:= :rpc.resource :cr.resource]]
                                                  :where [:= :cr.catalog_id :c.id]}}
                             "edges" {:type :json
                                      :queryable? false
                                      :expandable? true
                                      :field {:select [(h/json-agg
                                                        (h/row-to-json
                                                         (h/row
                                                          :sources.type :sources.title :targets.type :targets.title
                                                          :edges.type)))]
                                              :from [:edges]
                                              :join [[:catalog_resources :sources]
                                                     [:= :edges.source :sources.resource]

                                                     [:catalog_resources :targets]
                                                     [:= :edges.target :targets.resource]]
                                              :where [:= :edges.certname :c.certname]}}}

               :selection {:from [[:catalogs :c]]
                           :left-join [[:environments :e]
                                       [:= :c.environment_id :e.id]]}

               :alias "catalogs"
               :subquery? false
               :source-table "catalogs"}))

(def resources-query
  "Query for the top level resource entity"
  (map->Query {:projections {"certname" {:type  :string
                                         :queryable? true
                                         :field :c.certname}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :e.name}
                             "resource" {:type :string
                                         :queryable? true
                                         :field :cr.resource}
                             "type" {:type :string
                                     :queryable? true
                                     :field :type}
                             "title" {:type :string
                                      :queryable? true
                                      :field :title}
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
                                           :queryable true
                                           :field :rpc.parameters}}

               :selection {:from [[:catalog_resources :cr]]
                           :join [[:catalogs :c]
                                  [:= :cr.catalog_id :c.id]]
                           :left-join [[:environments :e]
                                       [:= :c.environment_id :e.id]

                                       [:resource_params_cache :rpc]
                                       [:= :rpc.resource :cr.resource]]}

               :alias "resources"
               :subquery? false}))

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
                                       :field :reports.hash}
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
                                            :field :environments.name}
                             "latest_report?" {:type :boolean
                                               :queryable? true
                                               :query-only? true}}
               :selection {:from [:resource_events]
                           :join [:reports
                                  [:= :resource_events.report_id :reports.id]]
                           :left-join [:environments
                                       [:= :reports.environment_id :environments.id]]}

               :alias "events"
               :subquery? false
               :entity :events
               :source-table "resource_events"}))

(def latest-report-query
  "Usually used as a subquery of reports"
  (map->Query {:projections {"latest_report_hash" {:type :string
                                                   :queryable? true
                                                   :field :reports.hash}}
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
                                     :field :name}}
               :selection {:from [:environments]}

               :alias "environments"
               :subquery? false
               :source-table "environments"}))

(def factsets-query
  "Query for the top level facts query"
  (map->Query {:projections {"timestamp" {:type :timestamp
                                          :queryable? true
                                          :field :timestamp}
                             "facts" {:type :json
                                      :queryable? true
                                      :expandable? true
                                      :field {:select [(h/json-agg
                                                        (h/row-to-json
                                                         (h/row
                                                          :fact_paths.path
                                                          (h/coalesce :fact_values.value_string
                                                                      :fact_values.value_json
                                                                      (h/scast :fact_values.value_boolean :text))
                                                          :fact_values.value_integer
                                                          :fact_values.value_float
                                                          :value_types.type)))]
                                              :from [:facts]
                                              :join [:fact_values
                                                     [:= :fact_values.id :facts.fact_value_id]

                                                     :fact_paths
                                                     [:= :fact_paths.id :facts.fact_path_id]

                                                     :value_types
                                                     [:= :value_types.id :fact_paths.value_type_id]]
                                              :where [:and
                                                      [:= :depth 0]
                                                      [:= :facts.factset_id :factsets.id]]}}
                             "certname" {:type :string
                                         :queryable? true
                                         :field :factsets.certname}
                             "hash" {:type :string
                                     :queryable? true
                                     :field :factsets.hash}
                             "producer_timestamp" {:type :timestamp
                                                   :queryable? true
                                                   :field :factsets.producer_timestamp}
                             "environment" {:type :string
                                            :queryable? true
                                            :field :environments.name}}

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
  ;; TODO: clean this up later, maybe generalize
  (->> projections
       (remove (comp (complement :queryable?) val))
       keys
       (into [])))

(defn projectable-fields
  "Returns a list of projectable fields from a query record.

  Fields marked as :query-only? true are unable to be projected and thus are
  excluded."
  [{:keys [projections]}]
  (->> projections
       (remove (comp :query-only? val))
       keys
       (into [])))

(defn extract-fields
  [[name {:keys [query-only? expandable? field]}] expand?]
  "Return all fields from a projection, if expand? true. If expand? false,
  returns all fields except for expanded ones, which are returned as [:null k].

  Return nil for fields which are query-only? since these can't be projected
  either."
  (when-not query-only?
    (if expand?
      [field name]
      (if expandable?
        [:null name]
        [field name]))))

(defn honeysql-from-query
  [{:keys [selection projections paging-options] :as query}]
  "Convert a query to honeysql format"
  (let [expand? (:expand? paging-options)]
    (log/spy (-> selection
                 (assoc :select (remove nil? (map #(extract-fields % expand?)
                                                  projections)))))))

(pls/defn-validated sql-from-query :- String
  [query]
  "Convert a query to honeysql, then to sql"
  (log/spy (-> query
               honeysql-from-query
               hcore/format
               first)))

(defn maybe-vectorize-string
  [arg]
  (if (vector? arg) arg [arg]))

(defprotocol SQLGen
  (-plan->sql [query] "Given the `query` plan node, convert it to a SQL string"))

(defn parenthize
  "Wrap `s` in parens"
  [s]
  (str " ( " s " ) "))

(extend-protocol SQLGen
  Query
  (-plan->sql [query]
    (let [has-where? (boolean (:where query))
          has-projections? (not (empty? (:projected-fields query)))
          update-when (fn [m pred ks f]
                        (if pred
                          (update-in m ks f)
                          m))
          sql (-> query
                  (update-when has-where? [:selection] #(hsql/merge-where % (htypes/raw (-plan->sql (:where query)))))
                  (update-when has-projections? [:projections] #(select-keys % (:projected-fields query)))
                  sql-from-query)]
      (if (:subquery? query)
        (parenthize sql)
        sql)))

  InExpression
  (-plan->sql [expr]
    (format "(%s) IN %s"
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
    (su/sql-regexp-array-match (:table expr) (:column expr)))

  NullExpression
  (-plan->sql [expr]
    (format "%s IS %s"
            (-plan->sql (:column expr))
            (if (:null? expr)
              "NULL"
              "NOT NULL")))

  AndExpression
  (-plan->sql [expr]
    (parenthize (str/join " AND " (map -plan->sql (:clauses expr)))))

  OrExpression
  (-plan->sql [expr]
    (parenthize (str/join " OR " (map -plan->sql (:clauses expr)))))

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
            (let [entity (get-in (meta node) [:query-context :entity])
                  expanded-latest (case entity
                                    :reports
                                    ["in" "hash"
                                     ["extract" "latest_report_hash"
                                      ["select-latest-report"]]]

                                    :events
                                    ["in" "report"
                                     ["extract" "latest_report_hash"
                                      ["select-latest-report"]]]

                                    (throw (IllegalArgumentException.
                                             (format "Field 'latest_report?' not supported on endpoint '%s'" entity))))]
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
  (contains? (ks/keyset user-query->logical-obj)
             (first expr)))

(defn create-extract-node
  "Returns a `query-rec` that has the correct projection for the given
  `column-list`. Updating :projected-fields causes the select in the SQL query
  to be modified. Setting :late-projected-fields does not affect the SQL, but
  includes in the information for later removing the columns."
  [query-rec column-list expr]
  (if (or (nil? expr)
          (not (subquery-expression? expr)))
    (let [qr (assoc query-rec :where (user-node->plan-node query-rec expr))]
      (if (:late-project? query-rec)
        (assoc qr :late-projected-fields column-list)
        (assoc qr :projected-fields column-list)))
    (let [[subquery-name & subquery-expression] expr]
      (assoc (user-query->logical-obj subquery-name)
        :projected-fields column-list
        :where (when (seq subquery-expression)
                 (user-node->plan-node (user-query->logical-obj subquery-name)
                                       (first subquery-expression)))))))

(defn user-node->plan-node
  "Create a query plan for `node` in the context of the given query (as `query-rec`)"
  [query-rec node]
  (cm/match [node]
            [["=" column value]]
            (let [col-type (get-in query-rec [:projections column :type])]
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
            (let [col-type (get-in query-rec [:projections column :type])]
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
            (let [col-type (get-in query-rec [:projections column :type])]
              (case col-type
                :array
                (map->ArrayRegexExpression {:table (:source-table query-rec)
                                            :column column
                                            :value value})

                :multi
                (map->RegexExpression {:column (str column "_string")
                                       :value value})

                (map->RegexExpression {:column column
                                       :value value})))

            [["~>" column value]]
            (let [col-type (get-in query-rec [:projections column :type])]
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
  [query-rec paging-options user-query]
  (let [plan-node (user-node->plan-node query-rec user-query)
        projections (projectable-fields query-rec)]
    (if (instance? Query plan-node)
      (-> plan-node
          (update-in [:projected-fields] #(->> %
                                               (filter (set projections))
                                               (into []))))
      (-> query-rec
          (assoc :where plan-node)
          (assoc :paging-options paging-options)
          (assoc :projected-fields projections)))))

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
                  qfields (queryable-fields query-context)]
              (when (and (not (vec? field))
                         (not (contains? (set qfields) field)))
                {:node node
                 :state (conj state (format "'%s' is not a queryable object for %s, known queryable objects are %s"
                                            field
                                            (:alias query-context)
                                            (json/generate-string qfields)))}))

            ; This validation is only for top-level extract operator
            ; For in-extract operator validation, please see annotate-with-context function
            [["extract" field & _]]
            (let [query-context (:query-context (meta node))
                  qfields (queryable-fields query-context)
                  column-validation-message (validate-query-operation-fields
                                              field
                                              qfields
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

(pls/defn-validated basic-project
  "Returns a function will remove non-projected columns if projections is specified."
  [projected-fields :- [s/Keyword]]
  (if (seq projected-fields)
    #(select-keys % projected-fields)
    identity))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compile-user-query->sql
  "Given a user provided query and a Query instance, convert the
  user provided query to SQL and extract the parameters, to be used
  in a prepared statement"
  [query-rec user-query & [{:keys [count?] :as paging-options}]]
  (when paging-options
    (paging/validate-order-by! (map keyword (queryable-fields query-rec)) paging-options))
  (let [{:keys [plan params]} (->> user-query
                                   (push-down-context query-rec)
                                   expand-user-query
                                   (convert-to-plan query-rec paging-options)
                                   extract-all-params)
        sql (plan->sql plan)
        paged-sql (jdbc/paged-sql sql paging-options)
        result-query {:results-query (apply vector paged-sql params)
                      :projected-fields (map keyword (:late-projected-fields plan))}]
    (if count?
      (assoc result-query :count-query (apply vector (jdbc/count-sql sql) params))
      result-query)))
