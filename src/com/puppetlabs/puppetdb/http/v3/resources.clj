(ns com.puppetlabs.puppetdb.http.v3.resources
  (:require [com.puppetlabs.puppetdb.http.v2.resources :as v2-resources]
            [com.puppetlabs.puppetdb.http.query :as http-q])
  (:use [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only (wrap-with-paging-options)]))


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
  (v2-resources/build-resources-app (wrap-with-paging-options query-app)))
