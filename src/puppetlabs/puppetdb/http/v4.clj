(ns puppetlabs.puppetdb.http.v4
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.aggregate-event-counts :as aec]
            [puppetlabs.puppetdb.http.event-counts :as ec]
            [puppetlabs.puppetdb.http.catalogs :as catalogs]
            [puppetlabs.puppetdb.http.reports :as reports]
            [puppetlabs.puppetdb.http.events :as events]
            [puppetlabs.puppetdb.http.fact-names :as fact-names]
            [puppetlabs.puppetdb.http.fact-paths :as fact-paths]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.edges :as edges]
            [puppetlabs.puppetdb.http.factsets :as factsets]
            [puppetlabs.puppetdb.http.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.http.resources :as resources]
            [puppetlabs.puppetdb.http.nodes :as nodes]
            [puppetlabs.puppetdb.http.environments :as envs]
            [puppetlabs.puppetdb.http.index :as index]
            [bidi.bidi :as bidi]
            [bidi.ring :as bring]
            [puppetlabs.puppetdb.http.query :as http-q]))

(def version :v4)

(defn experimental-index-app
  [version]
  (bring/wrap-middleware (index/index-app version)
                         (fn [app]
                           (partial http/experimental-warning app  "The root endpoint is experimental"))))

(def v4-app
  {"" (experimental-index-app version)
   "/facts" (facts/facts-app version)
   "/edges" (edges/edges-app version)
   "/factsets" (factsets/factset-app version)
   "/fact-names" (fact-names/fact-names-app version)
   "/fact-contents" (fact-contents/fact-contents-app version)
   "/fact-paths" (fact-paths/fact-paths-app version)
   "/nodes" (nodes/node-app version)
   "/environments" (envs/environments-app version)
   "/resources" (resources/resources-app version)
   "/catalogs" (catalogs/catalog-app version)
   "/events" (events/events-app version)
   "/event-counts" (ec/event-counts-app version)
   "/aggregate-event-counts" (aec/aggregate-event-counts-app version)
   "/reports" (reports/reports-app version)})
