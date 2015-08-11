(ns puppetlabs.puppetdb.http.server
  "REST server

   Consolidates our disparate REST endpoints into a single Ring
   application."
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.v4 :refer [v4-app]]
            [puppetlabs.puppetdb.middleware :refer
             [wrap-with-puppetdb-middleware wrap-with-globals wrap-with-metrics]]
            [net.cgrand.moustache :refer [app]]
            [ring.util.response :as rr]))

(defn deprecated-app
  [app msg request]
  (let [result (app request)]
    (log/warn msg)
    (rr/header result "X-Deprecation" msg)))

(defn experimental-warning
  [app msg request]
  (let [result (app request)]
    (log/warn msg)
    (rr/header result "Warning" msg)))

(defn- refuse-retired-api
  [version]
  (constantly
   (http/error-response
    (format "The %s API has been retired; please use v4" version)
    404)))

(def routes
  (app
   ["v4" &] {:any v4-app}
   ["v1" &] {:any (refuse-retired-api "v1")}
   ["v2" &] {:any (refuse-retired-api "v2")}
   ["v3" &] {:any (refuse-retired-api "v3")}))

(defn build-app
  "Generates a Ring application that handles PuppetDB requests.
  If get-authorizer is nil or false, all requests will be accepted.
  Otherwise it must accept no arguments and return an authorize
  function that accepts a request.  The request will be allowed only
  if authorize returns :authorized.  Otherwise, the return value
  should be a message describing the reason that access was denied."
  [get-authorizer get-shared-globals]
  (-> routes
      (wrap-with-puppetdb-middleware get-authorizer)
      (wrap-with-metrics (atom {}) http/leading-uris)
      (wrap-with-globals get-shared-globals)))
