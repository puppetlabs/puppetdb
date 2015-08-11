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
  "Generates a Ring application that handles metrics requests.
  If get-authorizer is nil or false, all requests will be accepted.
  Otherwise it must accept no arguments and return an authorize
  function that accepts a request.  The request will be allowed only
  if authorize returns :authorized.  Otherwise, the return value
  should be a message describing the reason that access was denied."
  [get-authorizer]
  (-> routes
      (wrap-with-puppetdb-middleware get-authorizer)))
