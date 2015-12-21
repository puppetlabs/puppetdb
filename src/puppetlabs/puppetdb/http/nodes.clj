(ns puppetlabs.puppetdb.http.nodes
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [validate-query-params
                                                    wrap-with-parent-check
                                                    wrap-with-parent-check']]
            [puppetlabs.puppetdb.http :as http]
            [bidi.bidi :as bidi]
            [bidi.ring :as bring]))

(defn node-status
  "Produce a response body for a single environment."
  [api-version node options]
  (let [status (first
                (eng/stream-query-result api-version
                                         ["from" "nodes" ["=" "certname" node]]
                                         {}
                                         options))]
    (if status
      (http/json-response status)
      (http/status-not-found-response "node" node))))

(defn node-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {"" (http-q/query-route-from' "nodes" version param-spec
                                  [http-q/restrict-query-to-active-nodes])
     ["/" :node] (-> (fn [{:keys [globals route-params]}]
                   (node-status version
                                (:node route-params)
                                (select-keys globals [:scf-read-db :url-prefix :warn-experimental])))
                 ;; Being a singular item, querying and pagination don't really make
                 ;; sense here
                 (validate-query-params {})) 
     ["/" :node "/facts"]
     (bring/wrap-middleware (f/facts-app version true http-q/restrict-query-to-node')
                            (fn [app] (wrap-with-parent-check' app version :node)))
     ["/" :node "/resources"]
     (bring/wrap-middleware (r/resources-app version true http-q/restrict-query-to-node')
                            (fn [app] (wrap-with-parent-check' app version :node)))}))
