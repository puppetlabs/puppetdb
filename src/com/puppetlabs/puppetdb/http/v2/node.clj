(ns com.puppetlabs.puppetdb.http.v2.node
  (:require [com.puppetlabs.puppetdb.http.v1.node :as v1-node]
            [com.puppetlabs.puppetdb.http.v2.facts :as f]
            [com.puppetlabs.puppetdb.http.v2.resources :as r]
            [com.puppetlabs.puppetdb.http.v2.status :as s]
            [com.puppetlabs.puppetdb.http.query :as http-q])
  (:use [net.cgrand.moustache :only (app)]
        [com.puppetlabs.middleware :only (verify-accepts-json)]))

(def routes
  (app
    []
    (comp v1-node/node-app http-q/restrict-query-to-active-nodes)

    [node "facts" &]
    (comp f/facts-app (partial http-q/restrict-query-to-node node))

    [node "resources" &]
    (comp r/resources-app (partial http-q/restrict-query-to-node node))

    [node]
    s/status-app))

(def node-app
  (verify-accepts-json routes))
