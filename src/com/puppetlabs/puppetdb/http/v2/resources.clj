(ns com.puppetlabs.puppetdb.http.v2.resources
  (:require [com.puppetlabs.puppetdb.http.v3.resources :as v3-resources]
            [com.puppetlabs.puppetdb.http.query :as http-q])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals]}]
                  (v3-resources/produce-body
                    (:resource-query-limit globals)
                    (params "query")
                    {}
                    (:scf-db globals)))
                http-q/restrict-query-to-active-nodes)}))

(def resources-app
  (v3-resources/build-resources-app
    (validate-query-params query-app {:optional ["query"]})))


