(ns puppetlabs.puppetdb.http.fact-contents
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn routes
  [version]
  (app
    []
    (http-q/query-route :fact-contents version http-q/restrict-query-to-active-nodes')))

(defn fact-contents-app
  [version]
  (-> (routes version)
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
