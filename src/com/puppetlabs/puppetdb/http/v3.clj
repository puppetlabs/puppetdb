(ns com.puppetlabs.puppetdb.http.v3
  (:require [com.puppetlabs.puppetdb.http.version :as ver]
            [com.puppetlabs.puppetdb.http.command :as cmd]
            [com.puppetlabs.puppetdb.http.metrics :as met]
            [com.puppetlabs.puppetdb.http.server-time :as st]
            [com.puppetlabs.puppetdb.http.aggregate-event-counts :as aec]
            [com.puppetlabs.puppetdb.http.event-counts :as ec]
            [com.puppetlabs.puppetdb.http.catalogs :as catalogs]
            [com.puppetlabs.puppetdb.http.reports :as reports]
            [com.puppetlabs.puppetdb.http.events :as events]
            [com.puppetlabs.puppetdb.http.fact-names :as fact-names]
            [com.puppetlabs.puppetdb.http.facts :as facts]
            [com.puppetlabs.puppetdb.http.resources :as resources]
            [com.puppetlabs.puppetdb.http.nodes :as nodes]
            [com.puppetlabs.puppetdb.http.environments :as envs]
            [net.cgrand.moustache :as moustache]))

(def version :v3)

(def v3-app
  (moustache/app
   ["commands"]
   {:any (cmd/command-app version)}

   ["facts" &]
   {:any (facts/facts-app version)}

   ["fact-names" &]
   {:any (fact-names/fact-names-app version)}

   ["nodes" &]
   {:any (nodes/node-app version)}

   ["environments" &]
   {:any (envs/environments-app version)}

   ["resources" &]
   {:any (resources/resources-app version)}

   ["metrics" &]
   (moustache/app
    ["mbeans"]
    {:get met/list-mbeans}

    ["mbean" & names]
    {:get (moustache/app
           (met/mbean names))})

   ["version" &]
   (moustache/app
    [""]
    {:get ver/current-version}

    ["latest"]
    {:get ver/latest-version})

   ["catalogs" &]
   {:any (catalogs/catalog-app version)}

   ["events" &]
   {:any (events/events-app version)}

   ["event-counts" &]
   {:any (ec/event-counts-app version)}

   ["aggregate-event-counts" &]
   {:any (aec/aggregate-event-counts-app version)}

   ["reports" &]
   {:any (reports/reports-app version)}

   ["server-time" &]
   {:any st/server-time-app}))
