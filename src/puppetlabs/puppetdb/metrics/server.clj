(ns puppetlabs.puppetdb.metrics.server
  (:require [puppetlabs.puppetdb.metrics.core :as metrics]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-puppetdb-middleware]]))

(def v1-app
  (app
    []
    {:get metrics/list-mbeans}

    [& names]
    {:get (app (metrics/mbean names))}))

(def routes
  (app
   ["v1" "mbeans" &]
   {:any v1-app}))

(defn build-app
  "Generate a Ring application that handles metrics requests

   Takes an `options` map which may contain the following keys:

  * `authorizer` - a function that takes a request and returns a
    :authorized if the request is authorized, or a user-visible reason if not.
    If not supplied, we default to authorizing all requests."
  [{:keys [authorizer]}]
  (-> routes
      (wrap-with-puppetdb-middleware authorizer)))
