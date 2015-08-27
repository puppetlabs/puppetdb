(ns puppetlabs.puppetdb.http.environments
  (:require [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.events :as ev]
            [puppetlabs.puppetdb.http.reports :as rp]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options wrap-with-parent-check]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection get-result-count]]))

(defn environment-status
  "Produce a response body for a single environment."
  [api-version environment db]
  (let [status (first
                (eng/stream-query-result :environments
                                         api-version
                                         ["=" "name" environment]
                                         {}
                                         db
                                         ""))]
    (if status
      (http/json-response status)
      (http/status-not-found-response "environment" environment))))

(defn routes
  [version]
  (app
   []
    (http-q/query-route :environments version identity)

   [environment]
   {:get
    (-> (fn [{:keys [globals]}]
          (environment-status version environment (:scf-read-db globals)))
        ;; Being a singular item, querying and pagination don't really make
        ;; sense here
        (validate-query-params {}))}

   [environment "facts" &]
   (-> (f/facts-app version true (partial http-q/restrict-query-to-environment' environment))
       (wrap-with-parent-check version :environment environment))

   [environment "resources" &]
   (-> (r/resources-app version true (partial http-q/restrict-query-to-environment' environment))
       (wrap-with-parent-check version :environment environment))

   [environment "events" &]
   (-> (comp (ev/events-app version) (partial http-q/restrict-query-to-environment environment))
       (wrap-with-parent-check version :environment environment))

   [environment "reports" &]
   (-> (comp (rp/reports-app version) (partial http-q/restrict-query-to-environment environment))
       (wrap-with-parent-check version :environment environment))))

(defn environments-app
  [version]
  (-> (routes version)
      verify-accepts-json
      wrap-with-paging-options))
