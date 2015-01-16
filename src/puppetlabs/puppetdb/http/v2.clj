(ns puppetlabs.puppetdb.http.v2
  (:require [puppetlabs.puppetdb.http.version :as ver]
            [puppetlabs.puppetdb.http.command :as cmd]
            [puppetlabs.puppetdb.metrics.core :as met]
            [puppetlabs.puppetdb.http.fact-names :as fact-names]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.resources :as resources]
            [puppetlabs.puppetdb.http.nodes :as nodes]
            [puppetlabs.puppetdb.http.environments :as envs]
            [net.cgrand.moustache :as moustache]))

(def version :v2)

(def v2-app
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
    {:get ver/latest-version})))
