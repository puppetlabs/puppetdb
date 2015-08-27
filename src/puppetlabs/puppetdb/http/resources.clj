(ns puppetlabs.puppetdb.http.resources
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn routes
  [version restrict-to-active-nodes optional-handlers]
  (let [handler (if restrict-to-active-nodes
                  http-q/restrict-query-to-active-nodes'
                  identity)
        handlers (if optional-handlers
                   (cons handler optional-handlers)
                   [handler])
        query-route #(apply (partial http-q/query-route :resources version) %)]
    (app
      []
      (query-route handlers)

      [type title &]
      (query-route (concat handlers
                           [(partial http-q/restrict-resource-query-to-type' type)
                            (partial http-q/restrict-resource-query-to-title' title)]))

      [type &]
      (query-route (concat handlers
                           [(partial http-q/restrict-resource-query-to-type' type)])))))

(defn resources-app
  ([version] (resources-app version true))
  ([version restrict-to-active-nodes & optional-handlers]
   (-> (routes version restrict-to-active-nodes optional-handlers)
       (validate-query-params
         {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
