(ns puppetlabs.puppetdb.http.nodes
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options wrap-with-parent-check]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]))

(defn node-status
  "Produce a response body for a single environment."
  [api-version node db url-prefix]
  (let [status (first
                (eng/stream-query-result :nodes
                                         api-version
                                         ["=" "certname" node]
                                         {}
                                         db
                                         url-prefix))]
    (if status 
      (http/json-response status)
      (http/status-not-found-response "node" node))))

(defn routes
  [{:keys [scf-read-db url-prefix api-version] :as globals}]
  (app
   []
   (http-q/query-route :nodes globals http-q/restrict-query-to-active-nodes')

   [node]
   {:get (-> (constantly
              (node-status api-version node scf-read-db url-prefix))
             (validate-query-params {}))}

   [node "facts" &]
   (-> (comp (f/facts-app globals) (partial http-q/restrict-query-to-node node))
       (wrap-with-parent-check globals :node node))

   [node "resources" &]
   (-> (comp (r/resources-app globals) (partial http-q/restrict-query-to-node node))
       (wrap-with-parent-check globals :node node))))

(defn node-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      (validate-query-params {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
