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
            [net.cgrand.moustache :as moustache]))

(def version :v4)

(defn experimental-index-app
  [version]
  (fn [request]
    (http/experimental-warning
     (index/index-app version)
     "The root endpoint is experimental"
     request)))

(def v4-app
  (moustache/app
   []
   {:any (experimental-index-app version)}

   ["facts" &]
   {:any (facts/facts-app version)}

   ["edges" &]
   {:any (edges/edges-app version)}

   ["factsets" &]
   {:any  (factsets/factset-app version)}

   ["fact-names" &]
   {:any (fact-names/fact-names-app version)}

   ["fact-contents" &]
   {:any (fact-contents/fact-contents-app version)}

   ["fact-paths" &]
   {:any (fact-paths/fact-paths-app version)}

   ["nodes" &]
   {:any (nodes/node-app version)}

   ["environments" &]
   {:any (envs/environments-app version)}

   ["resources" &]
   {:any (resources/resources-app version)}

   ["catalogs" &]
   {:any (catalogs/catalog-app version)}

   ["events" &]
   {:any (events/events-app version)}

   ["event-counts" &]
   {:any (ec/event-counts-app version)}

   ["aggregate-event-counts" &]
   {:any (aec/aggregate-event-counts-app version)}

   ["reports" &]
   {:any (reports/reports-app version)}))
