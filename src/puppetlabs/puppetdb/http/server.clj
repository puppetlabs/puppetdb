(ns puppetlabs.puppetdb.http.server
  "REST server

   Consolidates our disparate REST endpoints into a single Ring
   application."
  (:require [puppetlabs.puppetdb.http.aggregate-event-counts :as aec]
            [puppetlabs.puppetdb.http.event-counts :as ec]
            [puppetlabs.puppetdb.http.catalogs :as catalogs]
            [puppetlabs.puppetdb.http.reports :as reports]
            [puppetlabs.puppetdb.http.events :as events]
            [puppetlabs.puppetdb.http.fact-names :as fact-names]
            [puppetlabs.puppetdb.http.fact-paths :as fact-paths]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.edges :as edges]
            [puppetlabs.puppetdb.http.factsets :as factsets]
            [puppetlabs.puppetdb.http.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.http.resources :as resources]
            [puppetlabs.puppetdb.http.nodes :as nodes]
            [puppetlabs.puppetdb.http.environments :as envs]
            [puppetlabs.puppetdb.http :as http]
            [net.cgrand.moustache :as moustache]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-puppetdb-middleware wrap-with-metrics]]
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

(defn query-app
  [globals]
  (moustache/app
   ["facts" &] {:any (facts/facts-app globals)}
   ["edges" &] {:any (edges/edges-app globals)}
   ["factsets" &] {:any  (factsets/factset-app globals)}
   ["fact-names" &] {:any (fact-names/fact-names-app globals)}
   ["fact-contents" &] {:any (fact-contents/fact-contents-app globals)}
   ["fact-paths" &] {:any (fact-paths/fact-paths-app globals)}
   ["nodes" &] {:any (nodes/node-app globals)}
   ["environments" &] {:any (envs/environments-app globals)}
   ["resources" &] {:any (resources/resources-app globals)}
   ["catalogs" &] {:any (catalogs/catalog-app globals)}
   ["events" &] {:any (events/events-app globals)}
   ["event-counts" &] {:any (ec/event-counts-app globals)}
   ["aggregate-event-counts" &] {:any (aec/aggregate-event-counts-app globals)}
   ["reports" &] {:any (reports/reports-app globals)}))

(defn routes
  [globals]
  (moustache/app
   ["v4" &] {:any (-> globals
                      (assoc :api-version :v4)
                      query-app)}
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
