(ns com.puppetlabs.puppetdb.http.v3.resources
  (:require [com.puppetlabs.puppetdb.http.v2.resources :as v2-resources]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.http.paging :as paging])
  (:use [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only (validate-query-params wrap-with-paging-options)]))


(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options]}]
                  (v2-resources/produce-body
                    (:resource-query-limit globals)
                    (params "query")
                    paging-options
                    (:scf-db globals)))
            http-q/restrict-query-to-active-nodes)}))

(def resources-app
  (v2-resources/build-resources-app
    (-> query-app
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      (wrap-with-paging-options))))
