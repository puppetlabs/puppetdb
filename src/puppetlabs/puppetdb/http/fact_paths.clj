(ns puppetlabs.puppetdb.http.fact-paths
  (:require [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  [globals]
  (app
   [&]
   {:get (comp (fn [{:keys [params paging-options] :as request}]
                 (produce-streaming-body
                  :fact-paths
                  (:api-version globals)
                  (params "query")
                  paging-options
                  (:scf-read-db globals)
                  (:url-prefix globals))))}))

(defn routes
  [query-app]
  (app
   []
   (verify-accepts-json query-app)))

(defn fact-paths-app
  [globals]
  (routes
   (-> (query-app globals)
       verify-accepts-json
       (validate-query-params {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
