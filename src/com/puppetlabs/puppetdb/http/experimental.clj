(ns com.puppetlabs.puppetdb.http.experimental
  (:use [com.puppetlabs.puppetdb.http.experimental.catalog :only (catalog-app)]
        [com.puppetlabs.puppetdb.http.experimental.population :only (population-app)]
        [net.cgrand.moustache :only (app)]))

(def experimental-app
  (app
   ["catalog" &]
   {:get catalog-app}

   ["population" &]
   {:get population-app}))
