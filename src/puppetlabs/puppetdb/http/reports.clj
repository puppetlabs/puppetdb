(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  [version]
  (app
    [&]
    {:get  (fn [{:keys [params globals paging-options] :as request}]
             (produce-streaming-body
               :reports
               version
               (params "query")
               paging-options
               (:scf-read-db globals)))}))

(defn build-reports-app
  [query-app]
  (app
   []
   (verify-accepts-json query-app)))

(defn reports-app
  [version]
  (build-reports-app
   (-> (query-app version)
       (validate-query-params
        {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
