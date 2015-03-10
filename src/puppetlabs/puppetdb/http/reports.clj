(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    validate-query-params
                                                    wrap-with-paging-options]]))

(defn specific-report-app
  [version report-hash]
  (let [base-query ["=" "hash" report-hash]
        make-reponse-fn (fn [query]
                          (fn [{:keys [globals paging-options]}]
                            (produce-streaming-body
                              :reports
                              version
                              (json/generate-string query)
                              paging-options
                              (:scf-read-db globals))))]
    (app
      []
      (make-reponse-fn base-query)
      ["logs"]
      (make-reponse-fn ["extract" ["logs"] base-query])
      ["metrics"]
      (make-reponse-fn ["extract" ["metrics"] base-query]))))

(defn query-app
  [version]
  (app
    []
    {:get  (fn [{:keys [params globals paging-options]}]
             (produce-streaming-body
               :reports
               version
               (params "query")
               paging-options
               (:scf-read-db globals)))}
    [report-hash &]
    {:get (specific-report-app version report-hash)}))

(defn reports-app
  [version]
  (-> (query-app version)
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options
      verify-accepts-json))
