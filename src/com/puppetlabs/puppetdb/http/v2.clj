(ns com.puppetlabs.puppetdb.http.v2
  (:require [com.puppetlabs.puppetdb.http.version :as ver]
            [com.puppetlabs.puppetdb.http.command :as cmd]
            [com.puppetlabs.puppetdb.http.metrics :as met])
  (:use [com.puppetlabs.puppetdb.http.v2.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v2.fact-names :only (fact-names-app)]
        [com.puppetlabs.puppetdb.http.v2.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v2.resources :only (resources-app)]
        [net.cgrand.moustache :only (app)]))

(def v2-app
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
    {:get ver/latest-version})))
