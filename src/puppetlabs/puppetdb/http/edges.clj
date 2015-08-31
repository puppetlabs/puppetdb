(ns puppetlabs.puppetdb.http.edges
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.query.paging :as paging]))

(defn routes
  ([version] (routes version true))
  ([version restrict-to-active-nodes optional-handlers]
   (let [handler (if restrict-to-active-nodes
                   http-q/restrict-query-to-active-nodes'
                   identity)
         handlers (cons handler optional-handlers)
         param-spec {:optional (cons "query" paging/query-params)}
         query-route #(apply (partial http-q/query-route :edges version param-spec) %)]
     (app
       [""]
       (query-route handlers)))))

(defn edges-app
  ([version] (edges-app version true))
  ([version restrict-to-active-nodes & optional-handlers]
   (-> (routes version restrict-to-active-nodes optional-handlers)
       verify-accepts-json
       (validate-query-params
         {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
