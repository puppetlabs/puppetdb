(ns com.puppetlabs.puppetdb.http.experimental
  (:use [com.puppetlabs.puppetdb.http.catalog :only (catalog-app)]
        [com.puppetlabs.puppetdb.http.population :only (population-app)]
        [net.cgrand.moustache :only (app)]))

(def experimental-app
  (app
   ["catalog" node]
   {:get (fn [req]
           (catalog-app (assoc-in req [:params "node"] node)))}

   ["population" &]
   {:get population-app}))
