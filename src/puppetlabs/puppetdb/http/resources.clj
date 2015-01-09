(ns puppetlabs.puppetdb.http.resources
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query.resources :as r]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query :as query]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]))

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
  (build-resources-app
   (-> (query-app version)
     (validate-query-params
      {:optional (cons "query" paging/query-params)})
     wrap-with-paging-options)))
