(ns com.puppetlabs.puppetdb.http.v1
  (:require [com.puppetlabs.puppetdb.http.version :as ver]
            [com.puppetlabs.puppetdb.http.command :as cmd]
            [com.puppetlabs.puppetdb.http.metrics :as met])
  (:use [com.puppetlabs.puppetdb.http.v1.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v1.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v1.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v1.status :only (status-app)]
        [net.cgrand.moustache :only (app)]))

(def v1-app
  (app
   ["commands" &]
   {:any cmd/command}

   ["facts" &]
   {:any facts-app}

   ["nodes" &]
   {:any node-app}

   ["resources" &]
   {:any resources-app}

   ["status" &]
   {:any status-app}

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
