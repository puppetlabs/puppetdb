(ns com.puppetlabs.puppetdb.http.v2
  (:require         [com.puppetlabs.puppetdb.http.api :as api])
  (:use [com.puppetlabs.puppetdb.http.v2.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v2.fact-names :only (fact-names-app)]
        [com.puppetlabs.puppetdb.http.v2.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v2.resources :only (resources-app)]
        [net.cgrand.moustache :only (app)]))

(def v2-app
  (app
   ["commands"]
   {:any api/command}

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
