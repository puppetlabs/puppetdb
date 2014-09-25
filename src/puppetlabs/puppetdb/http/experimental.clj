(ns puppetlabs.puppetdb.http.experimental
  (:require [puppetlabs.puppetdb.http.experimental.population :refer [population-app]]
            [net.cgrand.moustache :refer [app]]))

(def experimental-app
  (app
   ["population" &]
   {:any population-app}))
