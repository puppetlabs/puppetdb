(ns puppetlabs.puppetdb.http.v3
  (:require [puppetlabs.puppetdb.http.version :as ver]
            [puppetlabs.puppetdb.http.command :as cmd]
            [puppetlabs.puppetdb.metrics.core :as met]
            [puppetlabs.puppetdb.http.server-time :as st]
            [puppetlabs.puppetdb.http.aggregate-event-counts :as aec]
            [puppetlabs.puppetdb.http.event-counts :as ec]
            [puppetlabs.puppetdb.http.reports :as reports]
            [puppetlabs.puppetdb.http.events :as events]
            [puppetlabs.puppetdb.http.fact-names :as fact-names]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.resources :as resources]
            [puppetlabs.puppetdb.http.nodes :as nodes]
            [puppetlabs.puppetdb.http.environments :as envs]
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
