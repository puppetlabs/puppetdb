(ns puppetlabs.puppetdb.http.fact-contents
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  [globals]
  (let [{:keys [url-prefix api-version scf-read-db]} globals]
    (app
     [&]
     {:get (comp (fn [{:keys [params paging-options] :as request}]
                   (produce-streaming-body
                    :fact-contents
                    api-version
                    (params "query")
                    paging-options
                    scf-read-db
                    url-prefix))
                 http-q/restrict-query-to-active-nodes)})))

(defn routes
  [query-app]
  (app
   []
   (verify-accepts-json query-app)))

(defn fact-contents-app
  [globals]
  (-> (query-app globals)
      (validate-query-params {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options
      routes))
