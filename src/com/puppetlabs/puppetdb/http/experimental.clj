(ns com.puppetlabs.puppetdb.http.experimental
  (:use [com.puppetlabs.puppetdb.http.experimental.catalog :only (catalog-app)]
        [com.puppetlabs.puppetdb.http.experimental.population :only (population-app)]
        [com.puppetlabs.puppetdb.http.experimental.event :only (events-app)]
        [com.puppetlabs.puppetdb.http.experimental.report :only (reports-app)]
        [net.cgrand.moustache :only (app)]))

(def experimental-app
  (app
   ["catalog" &]
   {:any catalog-app}

   ["population" &]
   {:any population-app}

   ["events" &]
   {:any events-app}

   ["reports" &]
   {:any reports-app}))
