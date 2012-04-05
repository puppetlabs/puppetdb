(ns com.puppetlabs.cmdb.http.experimental
  (:use [com.puppetlabs.cmdb.http.catalog :only (catalog-app)]
        [com.puppetlabs.cmdb.http.population :only (population-app)]
        [net.cgrand.moustache :only (app)]))

(def experimental-app
  (app
    ["catalog" node]
    {:get (fn [req]
            (catalog-app (assoc-in req [:params "node"] node)))}

    ["population" &]
    {:get population-app}))
