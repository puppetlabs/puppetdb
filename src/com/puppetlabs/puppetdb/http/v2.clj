(ns com.puppetlabs.puppetdb.http.v2
  (:require [com.puppetlabs.puppetdb.http.version :as ver]
            [com.puppetlabs.puppetdb.http.command :as cmd]
            [com.puppetlabs.puppetdb.http.metrics :as met]
            [com.puppetlabs.puppetdb.http.fact-names :as fact-names]
            [com.puppetlabs.puppetdb.http.facts :as facts]
            [com.puppetlabs.puppetdb.http.resources :as resources]
            [com.puppetlabs.puppetdb.http.nodes :as nodes]
            [net.cgrand.moustache :as moustache]))

(def version :v2)

(def v2-app
  (moustache/app
   ["commands"]
   {:any cmd/command}
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

