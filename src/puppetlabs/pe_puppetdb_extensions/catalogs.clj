(ns puppetlabs.pe-puppetdb-extensions.catalogs
  (:require [honeysql.core :as hcore]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.honeysql :as honeysql]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query-eng :as query-eng]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.query-eng.engine :as engine]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.http.handlers :as handlers]))

(def historical-catalog-query
  "Query for the top level catalogs entity"
  (engine/map->Query
   (-> engine/catalog-query
       (update :projections merge {"resources"
                                   {:type :json
                                    :queryable? false
                                    :field {:select [(honeysql/row-to-json :t)]
                                            :from [[{:select [[:c.resources :data]
                                                              [(engine/hsql-hash-as-href
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
                                                      [(engine/hsql-hash-as-href
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
      "catalog_uuid" {:type :uuid
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
                           :from [[{:select [[:cr.value :catalog_resources]
                                             [:rr.value :report_resources]]
                                    :from [[(hcore/call :jsonb_array_elements :r.resources) :rr]]
                                    :full-join [[(hcore/call :jsonb_array_elements :c.resources) :cr]
                                                [:and
                                                 [:=
                                                  (hcore/raw "cr.value->>'type'")
                                                  (hcore/raw "rr.value->>'resource_type'")]
                                                 [:=
                                                  (hcore/raw "cr.value->>'title'")
                                                  (hcore/raw "rr.value->>'resource_title'")]]]} :t]]}}
      "edges" {:type :json
               :queryable? false
               :field :c.edges}}

     :selection {:from [[:reports :r]]
                 :left-join [[:catalogs :c] [:= :c.transaction_uuid :r.transaction_uuid]
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

(def historical-catalogs-handler
  (handlers/create-query-handler :v1 "historical_catalogs"))

(def resource-graphs-handler
  (handlers/create-query-handler :v1 "resource_graphs"))

(defn merge-resources [resources]
  (->> (sutils/parse-db-json resources)
       (map (fn [{:keys [catalog_resources report_resources]}]
              (merge catalog_resources
                     (clojure.set/rename-keys report_resources
                                              {:resource_type :type
                                               :resource_title :title}))))))

(defn munge-resource-graph-rows
  [_ _]
  (fn [rows]
    (->> rows
         (map #(update % :resources merge-resources)))))

(defn turn-on-historical-catalogs!
  [historical-catalogs-limit]
  (reset! scf-storage/historical-catalogs-limit historical-catalogs-limit)
  (reset! scf-storage/store-catalogs-jsonb-columns? true)
  (swap! query-eng/entity-fn-idx merge
         {:historical-catalogs {:munge (constantly identity)
                                :rec historical-catalog-query}}
         {:resource-graphs {:munge munge-resource-graph-rows
                            :rec resource-graph-query}}))
