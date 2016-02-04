(ns puppetlabs.pe-puppetdb-extensions.server
  (:require [puppetlabs.pe-puppetdb-extensions.catalogs :as catalogs]
            [puppetlabs.pe-puppetdb-extensions.state-overview :as state-overview]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.http.handlers :as handlers]))

(defn v1-routes
  [query-fn get-shared-globals]
  (cmdi/context "/v1"
                (handlers/extract-query
                 (cmdi/ANY "/historical-catalogs" []
                           catalogs/historical-catalogs-handler))
                (handlers/extract-query
                 (cmdi/GET "/resource-graphs" []
                           catalogs/resource-graphs-handler))
                (cmdi/GET "/state-overview" []
                          (state-overview/state-overview-handler query-fn))))

(defn build-app
  [query-fn get-shared-globals]
  (-> (v1-routes query-fn get-shared-globals)
      mid/make-pdb-handler
      (mid/wrap-with-globals get-shared-globals)
      mid/verify-accepts-json))

