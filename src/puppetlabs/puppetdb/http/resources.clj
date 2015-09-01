(ns puppetlabs.puppetdb.http.resources
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    wrap-with-paging-options]]))

(defn routes
  [version restrict-to-active-nodes optional-handlers]
  (let [handler (if restrict-to-active-nodes
                  http-q/restrict-query-to-active-nodes'
                  identity)
        handlers (cons handler optional-handlers)
        param-spec {:optional (cons "query" paging/query-params)}
        query-route #(apply (partial http-q/query-route :resources version param-spec) %)]
    (app
      []
      (query-route handlers)

      [type title &]
      (query-route (concat handlers
                           [(partial http-q/restrict-resource-query-to-type type)
                            (partial http-q/restrict-resource-query-to-title title)]))

      [type &]
      (query-route (concat handlers
                           [(partial http-q/restrict-resource-query-to-type type)])))))

(defn resources-app
  ([version] (resources-app version true))
  ([version restrict-to-active-nodes & optional-handlers]
   (-> (routes version restrict-to-active-nodes optional-handlers)
       wrap-with-paging-options)))
