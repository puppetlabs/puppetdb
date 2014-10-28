(ns puppetlabs.puppetdb.http.fact-contents
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  [version]
  (app
   [&]
   {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                 (produce-streaming-body
                  :fact-contents
                  version
                  (params "query")
                  paging-options
                  (:scf-read-db globals)))
               http-q/restrict-query-to-active-nodes)}))

(defn routes
  [query-app]
  (app
   []
   (verify-accepts-json query-app)))

(defn fact-contents-app
  [version]
  (routes
   (-> (query-app version)
       (validate-query-params
        {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
