(ns puppetlabs.puppetdb.http.server
  "REST server

   Consolidates our disparate REST endpoints into a single Ring
   application."
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.v4 :refer [v4-app]]
            [puppetlabs.puppetdb.middleware :refer
             [wrap-with-puppetdb-middleware wrap-with-metrics]]
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

(defn routes
  [globals]
  (app
   ["v4" &] {:any (v4-app globals)}
   ["v1" &] {:any (refuse-retired-api "v1")}
   ["v2" &] {:any (refuse-retired-api "v2")}
   ["v3" &] {:any (refuse-retired-api "v3")}))

(defn build-app
  "Generate a Ring application that handles PuppetDB requests

  `globals` is a map containing global state useful
   to request handlers which may contain the following:

  * `authorizer` - a function that takes a request and returns a
    :authorized if the request is authorized, or a user-visible reason if not.
    If not supplied, we default to authorizing all requests."
  [{:keys [authorizer] :as globals}]
  (-> (routes globals)
      (wrap-with-puppetdb-middleware authorizer)
      (wrap-with-metrics (atom {}) http/leading-uris)))
