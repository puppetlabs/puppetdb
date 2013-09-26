(ns com.puppetlabs.puppetdb.http.experimental
  (:use [com.puppetlabs.puppetdb.http.experimental.planetarium-catalog :only (planetarium-catalog-app)]
        [com.puppetlabs.puppetdb.http.experimental.population :only (population-app)]
        [net.cgrand.moustache :only (app)]))

(def experimental-app
  (app
    ["planetarium-catalog" &]
    {:any planetarium-catalog-app}

    ["population" &]
    {:any population-app}))
