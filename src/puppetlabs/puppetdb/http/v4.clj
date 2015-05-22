(ns puppetlabs.puppetdb.http.v4
  (:require [puppetlabs.puppetdb.http.aggregate-event-counts :as aec]
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
            [net.cgrand.moustache :as moustache]))

(def api-version :v4)

(defn v4-app
  [raw-globals]
  (let [globals (assoc raw-globals :api-version api-version)]
    (moustache/app
     ["facts" &]
     {:any (facts/facts-app globals)}

     ["edges" &]
     {:any (edges/edges-app globals)}

     ["factsets" &]
     {:any  (factsets/factset-app globals)}

     ["fact-names" &]
     {:any (fact-names/fact-names-app globals)}

     ["fact-contents" &]
     {:any (fact-contents/fact-contents-app globals)}

     ["fact-paths" &]
     {:any (fact-paths/fact-paths-app globals)}

     ["nodes" &]
     {:any (nodes/node-app globals)}

     ["environments" &]
     {:any (envs/environments-app globals)}

     ["resources" &]
     {:any (resources/resources-app globals)}

     ["catalogs" &]
     {:any (catalogs/catalog-app globals)}

     ["events" &]
     {:any (events/events-app globals)}

     ["event-counts" &]
     {:any (ec/event-counts-app globals)}

     ["aggregate-event-counts" &]
     {:any (aec/aggregate-event-counts-app globals)}

     ["reports" &]
     {:any (reports/reports-app globals)})))
