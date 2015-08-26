(ns puppetlabs.puppetdb.http.facts
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
        query-route #(apply (partial http-q/query-route :facts version) %)]
  (app
    []
    (query-route handlers)

    [fact value &]
    (query-route (concat handlers
                         [(partial http-q/restrict-fact-query-to-name' fact)
                          (partial http-q/restrict-fact-query-to-value' value)]))

    [fact &]
    (query-route (concat handlers
                         [(partial http-q/restrict-fact-query-to-name' fact)])))))

(defn facts-app
  ([version]
   (facts-app version true))
  ([version restrict-to-active-nodes & optional-handlers]
   (-> (routes version restrict-to-active-nodes optional-handlers)
       (validate-query-params
         {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
