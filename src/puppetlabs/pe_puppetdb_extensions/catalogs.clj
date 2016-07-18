(ns puppetlabs.pe-puppetdb-extensions.catalogs
  (:require [honeysql.core :as hcore]
            [bidi.schema :as bidi-schema]
            [puppetlabs.comidi :as cmdi]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.honeysql :as honeysql]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng :as query-eng]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.query-eng.engine :as engine]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.http.handlers :as handlers]))

(defn hsql-hash-as-href
  [entity parent child]
  (hcore/raw (str "format("
                  (str "'/pdb/ext/v1/" (name parent) "/%s/" (name child) "'")
                  ", "
                  entity
                  ")")))

(def historical-catalog-query
  "Query for the top level catalogs entity"
  (engine/map->Query
   (-> engine/catalog-query
       (update :projections merge {"resources"
                                   {:type :json
                                    :queryable? false
                                    :field {:select [(honeysql/row-to-json :t)]
                                            :from [[{:select [[:c.resources :data]
                                                              [(hsql-hash-as-href
                                                                "c.catalog_uuid::text"
                                                                :historical-catalogs
                                                                :resources)
                                                               :href]]} :t]]}}
                                   "edges"
                                   {:type :json
                                    :queryable? false
                                    :field {:select [(honeysql/row-to-json :t)]
                                            :from [[{:select
                                                     [[:c.edges :data]
                                                      [(hsql-hash-as-href
                                                        "c.catalog_uuid::text"
                                                        :historical-catalogs
                                                        :edges)
                                                       :href]]} :t]]}}})
       (assoc :selection {:from [[:catalogs :c]]
                          :left-join [[:environments :e]
                                      [:= :c.environment_id :e.id]]}
              :alias "historical_catalogs"))))


(def resource-graph-query
  (engine/map->Query
    {:projections
     {"certname" {:type :string
                  :queryable? true
                  :field :r.certname}
      "catalog_uuid" {:type :string
                      :queryable? true
                      :field (engine/hsql-uuid-as-str :r.catalog_uuid)}
      "transaction_uuid" {:type :string
                          :queryable? true
                          :field (engine/hsql-uuid-as-str :r.transaction_uuid)}
      "producer_timestamp" {:type :timestamp
                            :queryable? true
                            :field :r.producer_timestamp}
      "noop" {:type :boolean
              :queryable? true
              :field :r.noop}
      "status" {:type :string
                :queryable? true
                :field :report_statuses.status}
      "code_id" {:type :string
                 :queryable? true
                 :field :c.code_id}
      "environment" {:type :string
                     :queryable? true
                     :field :e.environment}
      "resources" {:type :json
                   :queryable? false
                   :field {:select [(honeysql/json-agg (honeysql/row-to-json :t))]
                           :from [[{:select [[(honeysql/coalesce (hcore/raw "cr.value->>'type'")
                                                                 :rr.type) :type]
                                             [(honeysql/coalesce (hcore/raw "cr.value->>'title'")
                                                                 :rr.title) :title]
                                             [(hcore/raw "cr.value->>'file'") :file]
                                             [(hcore/raw "cr.value->>'line'") :line]
                                             [(hcore/raw "cr.value->>'exported'") :exported]
                                             [(hcore/call
                                               :cast (hcore/raw "cr.value->>'tags'") :jsonb)
                                              :tags]
                                             [(hcore/call
                                               :cast (hcore/raw "cr.value->>'parameters'") :jsonb)
                                              :parameters]
                                             :rr.events]
                                    :from [[{:select
                                             [[(honeysql/json-agg
                                                (hcore/call :json_build_object
                                                            (hcore/raw "'property'") :re.property
                                                            (hcore/raw "'status'") :re.status
                                                            (hcore/raw "'message'") :re.message
                                                            (hcore/raw "'new_value'") (honeysql/scast :re.new_value :jsonb)
                                                            (hcore/raw "'old_value'") (honeysql/scast :re.old_value :jsonb)
                                                            (hcore/raw "'timestamp'") :re.timestamp)) :events]
                                              [:re.resource_type :type]
                                              [:re.resource_title :title]]
                                             :from [[:resource_events :re]]
                                             :where [:= :r.id :re.report_id]
                                             :group-by [:re.resource_type :re.resource_title]} :rr]]
                                    :full-join [[(hcore/call :jsonb_array_elements :c.resources) :cr]
                                                [:and
                                                 [:= :rr.type (hcore/raw "cr.value->>'type'")]
                                                 [:= :rr.title (hcore/raw "cr.value->>'title'")]]]} :t]]}}
      "edges" {:type :json
               :queryable? false
               :field :c.edges}}

     :selection {:from [[:reports :r]]
                 :left-join [[:catalogs :c] [:= :c.catalog_uuid :r.catalog_uuid]
                             [:environments :e] [:= :r.environment_id :e.id]
                             :report_statuses [:= :r.status_id :report_statuses.id]]}

     :relationships {;; Parents - direct
                     "node" {:columns ["certname"]}
                     "environments" {:local-columns ["environment"]
                                     :foreign-columns ["name"]}

                     ;; Children - direct
                     "edges" {:columns ["certname"]}
                     "resources" {:columns ["certname"]}}
     :alias "resource_graphs"
     :subquery? false
     :entity :reports
     :source-table "reports"}))

(def historical-catalogs-child-data-query
  "Query intended to be used by the `/historical-catalogs/<hash>/resources` and
  `/historical-catalogs/<hash>/edges` endpoints used for digging into the child
  data for a specifc catalog."
  (engine/map->Query {:projections
                      {"resources" {:type :json
                                    :queryable? false
                                    :field :catalogs.resources}
                       "edges" {:type :json
                                :queryable? false
                                :field :catalogs.edges}
                       "catalog_uuid" {:type :string
                                       :queryable? true
                                       :field
                                       (engine/hsql-uuid-as-str :catalogs.catalog_uuid)}}
                      :selection {:from [:catalogs]}
                      :alias "historical_catalogs_children"
                      :subquery? false
                      :entity :catalogs
                      :source-table "catalogs"}))

(defn historical-catalogs-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity]
  (fn [{:keys [globals route-params]}]
    (let [query ["from" entity ["=" "catalog_uuid" (:catalog_uuid route-params)]]]
      (query-eng/produce-streaming-body version {:query query}
                                        (http-q/narrow-globals globals)))))

(pls/defn-validated historical-catalogs-routes :- bidi-schema/RoutePair
  [version]
  (cmdi/routes
   (cmdi/ANY "" []
     (handlers/create-query-handler version "historical_catalogs"))

   (cmdi/ANY ["/" :catalog_uuid "/edges"] []
     (-> (historical-catalogs-data-responder version "historical_catalog_edges")
         (mid/parent-check version :historical-catalog :catalog_uuid)
         mid/validate-no-query-params))

   (cmdi/ANY ["/" :catalog_uuid "/resources"] []
     (-> (historical-catalogs-data-responder version "historical_catalog_resources")
         (mid/parent-check version :historical-catalog :catalog_uuid)
         mid/validate-no-query-params))))

(def resource-graphs-handler
  (handlers/create-query-handler :v1 "resource_graphs"))

(defn turn-on-historical-catalogs!
  [historical-catalogs-limit]
  (when (<= historical-catalogs-limit 0)
    (jdbc/delete! :catalogs
                  ["id NOT IN (SELECT catalog_id FROM latest_catalogs)"]))
  (reset! scf-storage/historical-catalogs-limit
          (max 1 historical-catalogs-limit))
  (reset! scf-storage/store-catalogs-jsonb-columns?
          (>= historical-catalogs-limit 1))
  (swap! query-eng/entity-fn-idx merge
         {:historical-catalogs
          {:munge (constantly identity)
           :rec historical-catalog-query}

          :historical-catalog-resources
          {:munge (constantly (comp :resources first))
           :rec historical-catalogs-child-data-query}

          :historical-catalog-edges
          {:munge (constantly (comp :edges first))
           :rec historical-catalogs-child-data-query}

          :resource-graphs
          {:munge (constantly identity)
           :rec resource-graph-query}}))
