(ns puppetlabs.puppetdb.http.environments
  (:require [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.events :as ev]
            [puppetlabs.puppetdb.http.reports :as rp]
            [puppetlabs.puppetdb.middleware :refer [validate-query-params
                                                    wrap-with-parent-check]]
            [bidi.ring :as bring]))

(defn environment-status
  "Produce a response body for a single environment."
  [api-version environment options]
  (let [status (first
                (eng/stream-query-result api-version
                                         ["from" "environments" ["=" "name" environment]]
                                         {}
                                         options))]
    (if status
      (http/json-response status)
      (http/status-not-found-response "environment" environment))))

(defn environments-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params}]
    {"" (http-q/query-route-from' "environments" version param-spec)

     ["/" :environment]
     (bring/wrap-middleware {"" (fn [{:keys [globals route-params]}]
                                  (environment-status version (:environment route-params)
                                                      (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))}
                            (fn [app] (validate-query-params app {})))

     ["/" :environment "/facts"]
     (bring/wrap-middleware {"" (f/facts-app version true http-q/restrict-query-to-environment')}
                            (fn [app]
                              (fn [{:keys [route-params] :as req}]
                                ((wrap-with-parent-check app version :environment (:environment route-params)) req))))

     ["/" :environment "/resources"]
     (bring/wrap-middleware {"" (r/resources-app version true http-q/restrict-query-to-environment')}
                            (fn [app]
                              (fn [{:keys [route-params] :as req}]
                                ((wrap-with-parent-check app version :environment (:environment route-params)) req))))

     ["/" :environment "/events"]
     (bring/wrap-middleware {"" (ev/events-app version http-q/restrict-query-to-environment')}
                            (fn [app]
                              (fn [{:keys [route-params] :as req}]
                                ((wrap-with-parent-check app version :environment (:environment route-params)) req))))

     ["/" :environment "/reports"]
     (bring/wrap-middleware {"" (rp/reports-app version http-q/restrict-query-to-environment')}
                            (fn [app]
                              (fn [{:keys [route-params] :as req}]
                                ((wrap-with-parent-check app version :environment (:environment route-params)) req))))}))
