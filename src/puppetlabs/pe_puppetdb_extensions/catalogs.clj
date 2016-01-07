(ns puppetlabs.pe-puppetdb-extensions.catalogs
  (:require [compojure.core :as compojure]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query-eng :as query-eng]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.query-eng.engine :as engine]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]))

(def historical-catalog-query
  "Query for the top level catalogs entity"
  (engine/map->Query
   (-> engine/catalog-query
       (update :projections merge {"resources" {:type :json
                                                :queryable? false
                                                :field :c.resources}
                                   "edges" {:type :json
                                            :queryable? false
                                            :field :c.edges}})
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
      "catalog_resources" {:type :json
                           :queryable? false
                           :field :c.resources}
      "report_resources" {:type :json
                          :queryable? false
                          :field :r.resources}
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

(def historical-catalogs-app
  (let [param-spec {:optional paging/query-params}]
    (http-q/query-route-from "historical_catalogs" :v1 param-spec [])))

(def resource-graphs-app
  (let [param-spec {:optional paging/query-params}]
    (http-q/query-route-from "resource_graphs" :v1 param-spec [])))

(defn merge-resources [report-resources catalog-resources]
  (->> (clojure.set/join (sutils/parse-db-json report-resources)
                         (sutils/parse-db-json catalog-resources)
                         {:resource_type :type
                          :resource_title :title})
       (map #(dissoc % :resource_type :resource_title))))

(defn munge-resource-graph-rows
  [_ _]
  (fn [rows]
    (->> rows
         (map (comp #(assoc % :resources (merge-resources (:report_resources %)
                                                          (:catalog_resources %)))
                    #(dissoc % :catalog_resources :report_resources))))))

(defn turn-on-historical-catalogs!
  [store-historical-catalogs?]
  (when store-historical-catalogs?
    (reset! scf-storage/store-catalogs-historically? true))
  (reset! scf-storage/store-catalogs-jsonb-columns? true)
  (swap! query-eng/entity-fn-idx merge
         {:historical-catalogs {:munge (constantly identity)
                                :rec historical-catalog-query}}
         {:resource-graphs {:munge munge-resource-graph-rows
                            :rec resource-graph-query}}))
