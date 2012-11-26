(ns com.puppetlabs.puppetdb.http.experimental
  (:use [com.puppetlabs.puppetdb.http.experimental.catalog :only (catalog-app)]
        [com.puppetlabs.puppetdb.http.experimental.population :only (population-app)]
        [com.puppetlabs.puppetdb.http.experimental.explore :only (nodes-app facts-app resources-app)]
        [net.cgrand.moustache :only (app)]))

(def experimental-app
  (app
   ["nodes" &]
   {:any nodes-app}

   ["facts" &]
   {:any facts-app}

   ["resources" &]
   {:any resources-app}

   ["catalog" &]
   {:any catalog-app}

   ["population" &]
   {:any population-app}))
