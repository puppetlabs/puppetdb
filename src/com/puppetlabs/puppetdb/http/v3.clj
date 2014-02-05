(ns com.puppetlabs.puppetdb.http.v3
  (:require [com.puppetlabs.puppetdb.http.version :as ver]
            [com.puppetlabs.puppetdb.http.command :as cmd]
            [com.puppetlabs.puppetdb.http.metrics :as met])
  (:use [com.puppetlabs.puppetdb.http.v3.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v3.fact-names :only (fact-names-app)]
        [com.puppetlabs.puppetdb.http.v3.nodes :only (node-app)]
        [com.puppetlabs.puppetdb.http.v3.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v3.catalogs :only (catalog-app)]
        [com.puppetlabs.puppetdb.http.v3.events :only (events-app)]
        [com.puppetlabs.puppetdb.http.v3.reports :only (reports-app)]
        [com.puppetlabs.puppetdb.http.v3.event-counts :only (event-counts-app)]
        [com.puppetlabs.puppetdb.http.v3.aggregate-event-counts :only (aggregate-event-counts-app)]
        [com.puppetlabs.puppetdb.http.v3.server-time :only (server-time-app)]
        [net.cgrand.moustache :only (app)]))

(def v3-app
  (app
   ["commands"]
   {:any cmd/command}
   ["facts" &]
   {:any facts-app}

   ["fact-names" &]
   {:any fact-names-app}

   ["nodes" &]
   {:any node-app}

   ["resources" &]
   {:any resources-app}

   ["metrics" &]
   (app
    ["mbeans"]
    {:get met/list-mbeans}

    ["mbean" & names]
    {:get (app
           (met/mbean names))})

   ["version" &]
   (app
    [""]
    {:get ver/current-version}

    ["latest"]
    {:get ver/latest-version})

   ["catalogs" &]
   {:any catalog-app}

   ["events" &]
   {:any events-app}

   ["event-counts" &]
   {:any event-counts-app}

   ["aggregate-event-counts" &]
   {:any aggregate-event-counts-app}

   ["reports" &]
   {:any reports-app}

   ["server-time" &]
   {:any server-time-app}))
