(ns com.puppetlabs.puppetdb.http.resources
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resources :as r]
            [com.puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query :as query]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.http :as http]))

(defn query-app
  [version]
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options]}]
                  (produce-streaming-body
                    :resources
                    version
                    (params "query")
                    paging-options
                    (:scf-read-db globals)))
                http-q/restrict-query-to-active-nodes)}))

(defn build-resources-app
  [query-app]
  (app
    []
    (verify-accepts-json query-app)

    [type title &]
    (comp query-app
          (partial http-q/restrict-resource-query-to-type type)
          (partial http-q/restrict-resource-query-to-title title))

    [type &]
    (comp query-app
          (partial http-q/restrict-resource-query-to-type type))))

(defn resources-app
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (build-resources-app
         (-> (query-app version)
             (validate-query-params
              {:optional ["query"]})))
    (build-resources-app
      (-> (query-app version)
          (validate-query-params
           {:optional (cons "query" paging/query-params)})
          wrap-with-paging-options))))
