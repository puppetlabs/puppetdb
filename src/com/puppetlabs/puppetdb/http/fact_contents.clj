(ns com.puppetlabs.puppetdb.http.fact-contents
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query :as query]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.http :as http]))

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
