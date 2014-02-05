(ns com.puppetlabs.puppetdb.http.v1
  (:require [com.puppetlabs.puppetdb.http.api :as api])
  (:use [com.puppetlabs.puppetdb.http.v1.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v1.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v1.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v1.status :only (status-app)]
        [net.cgrand.moustache :only (app)]))

(def v1-app
  (app
   ["commands" &]
   {:any api/command}

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
    {:get api/list-mbeans}

    ["mbean" & names]
    {:get (app
           (api/mbean names))})

   ["version" &]
   (app
    [""]
    {:get api/current-version}

    ["latest"]
    {:get api/latest-version})))
