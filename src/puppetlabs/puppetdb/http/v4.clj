(ns puppetlabs.puppetdb.http.v4
  (:require [puppetlabs.comidi :as cmdi]
            [schema.core :as s]
            [puppetlabs.puppetdb.http.handlers :as handlers]))

(def v4-app
  (apply cmdi/routes
         (map (fn [[route-str handler]]
                (cmdi/context route-str (handler :v4)))
              {"" handlers/experimental-index-routes
               "/facts" handlers/facts-routes
               "/edges" handlers/edge-routes
               "/factsets" handlers/factset-routes
               "/fact-names" handlers/fact-names-routes
               "/fact-contents" handlers/fact-contents-routes
               "/fact-paths" handlers/fact-path-routes
               "/nodes" handlers/node-routes
               "/environments" handlers/environments-routes
               "/resources" handlers/resources-routes
               "/catalogs" handlers/catalog-routes
               "/events" handlers/events-routes
               "/event-counts" handlers/event-counts-routes
               "/aggregate-event-counts" handlers/agg-event-counts-routes 
               "/reports" handlers/reports-routes})))
