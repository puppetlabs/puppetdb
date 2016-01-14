(ns puppetlabs.pe-puppetdb-extensions.reports
  (:require [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query-eng :as query-eng]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng.engine :as engine]
            [puppetlabs.puppetdb.honeysql :as honeysql]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.http.handlers :as handlers]))

(defn reports-resources-routes
  [get-shared-globals]
  (-> (cmdi/routes
       (cmdi/GET ["/query/v4/reports/" (handlers/route-param :hash) "/resources"] []
                 (-> (fn [{:keys [globals route-params]}]
                       (let [query ["from" "report_resources" ["=" "hash" (:hash route-params)]]
                             opts (select-keys globals [:scf-read-db
                                                        :url-prefix
                                                        :pretty-print
                                                        :warn-experimental])]
                         (query-eng/produce-streaming-body :v4 {:query query} opts)))
                     (mid/parent-check :v4 :report :hash)
                     mid/verify-accepts-json
                     (mid/wrap-with-globals get-shared-globals))))
      mid/make-pdb-handler
      vector))

(def report-resources-query
  "Query intended to be used by the `/reports/<hash>/reosurces` endpoint
  used for digging into the resources for a specific report."
  (engine/map->Query {:projections
                      {"resources" {:type :json
                                    :queryable? false
                                    :field :reports.resources}
                       "hash" {:type :string
                               :queryable? true
                               :query-only? true
                               :field (engine/hsql-hash-as-str :reports.hash)}}
                      :selection {:from [:reports]}
                      :alias "resources"
                      :subquery? false
                      :entity :reports
                      :source-table "reports"}))

(def reports-with-resources-query
  (-> engine/reports-query
      (assoc-in [:projections "resources"]
                {:type :json
                 :queryable? false
                 :field
                 {:select [(honeysql/row-to-json :t)]
                  :from [[{:select
                           [[:resources :data]
                            [(engine/hsql-hash-as-href
                              (sutils/sql-hash-as-str "hash") :reports :resources)
                             :href]]} :t]]}})))

(defn turn-on-unchanged-resources!
  []
  (reset! scf-storage/store-resources-column? true)
  (swap! query-eng/entity-fn-idx merge
         {:report-resources {:munge (constantly (comp :resources first))
                             :rec report-resources-query}
          :reports {:munge (constantly identity)
                    :rec reports-with-resources-query}}))
