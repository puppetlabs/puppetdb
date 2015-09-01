(ns puppetlabs.puppetdb.http.nodes
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.query :as http-q]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    validate-query-params
                                                    wrap-with-paging-options
                                                    wrap-with-parent-check]]
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
  [version]
  (let [param-spec {:optional (cons "query" paging/query-params)}]
    (app
      []
      (http-q/query-route :nodes version param-spec http-q/restrict-query-to-active-nodes')

      [node]
      (-> (fn [{:keys [globals]}]
            (node-status version
                         node
                         (:scf-read-db globals)
                         (:url-prefix globals)))
          ;; Being a singular item, querying and pagination don't really make
          ;; sense here
          (validate-query-params {}))

      [node "facts" &]
      (-> (f/facts-app version true (partial http-q/restrict-query-to-node node))
          (wrap-with-parent-check version :node node))

      [node "resources" &]
      (-> (r/resources-app version true (partial http-q/restrict-query-to-node node))
          (wrap-with-parent-check version :node node)))))

(defn node-app
  [version]
  (-> (routes version)
    verify-accepts-json
    wrap-with-paging-options))
