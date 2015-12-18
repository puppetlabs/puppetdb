(ns puppetlabs.puppetdb.http.environments
  (:require [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.events :as ev]
            [puppetlabs.puppetdb.http.reports :as rp]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [validate-query-params
                                                    wrap-with-parent-check]]))

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
    (app
     []
     (http-q/query-route-from "environments" version param-spec)

     [environment]
     (-> (fn [{:keys [globals]}]
           (environment-status version environment
                               (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))
         ;; Being a singular item, querying and pagination don't really make
         ;; sense here
         (validate-query-params {}))

     [environment "facts" &]
     (-> (f/facts-app version true (partial http-q/restrict-query-to-environment environment))
         (wrap-with-parent-check version :environment environment))

     [environment "resources" &]
     (-> (r/resources-app version true (partial http-q/restrict-query-to-environment environment))
         (wrap-with-parent-check version :environment environment))

     [environment "events" &]
     (-> (ev/events-app version (partial http-q/restrict-query-to-environment environment))
         (wrap-with-parent-check version :environment environment))

     [environment "reports" &]
     (-> (rp/reports-app version (partial http-q/restrict-query-to-environment environment))
         (wrap-with-parent-check version :environment environment)))))
