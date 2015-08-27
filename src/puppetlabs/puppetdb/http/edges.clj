(ns puppetlabs.puppetdb.http.edges
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.query.paging :as paging]))

(defn routes
  ([version] (routes version true))
  ([version restrict-to-active-nodes optional-handlers]
   (let [handler (if restrict-to-active-nodes
                   http-q/restrict-query-to-active-nodes'
                   identity)
         handlers (if optional-handlers
                    (cons handler optional-handlers)
                    [handler])
         query-route #(apply (partial http-q/query-route :edges version) %)]
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
