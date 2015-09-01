(ns puppetlabs.puppetdb.http.fact-paths
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    validate-query-params
                                                    wrap-with-paging-options]]))

(defn routes
  [version]
  (let [param-spec {:optional (cons "query" paging/query-params)}]
    (app
      []
      (http-q/query-route :fact-paths version param-spec))))

(defn fact-paths-app
  [version]
  (-> (routes version)
      verify-accepts-json
      wrap-with-paging-options))
