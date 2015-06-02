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
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params wrap-with-paging-options]]
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
   {:get
    (-> (fn [{:keys [params globals paging-options]}]
          (produce-streaming-body
           :environments
           version
           (params "query")
           paging-options
           (:scf-read-db globals)
           (:url-prefix globals)))
        (validate-query-params
         {:optional (cons "query" paging/query-params)}))}

   [environment]
   {:get
    (-> (fn [{:keys [globals]}]
          (environment-status version environment (:scf-read-db globals)))
        ;; Being a singular item, querying and pagination don't really make
        ;; sense here
        (validate-query-params {}))}

   [environment "facts" &]
   {:get
    (comp (f/facts-app version) (partial http-q/restrict-query-to-environment environment))}

   [environment "resources" &]
   {:get
    (comp (r/resources-app version) (partial http-q/restrict-query-to-environment environment))}

   [environment "events" &]
   {:get
    (comp (ev/events-app version) (partial http-q/restrict-query-to-environment environment))}

   [environment "reports" &]
   {:get
    (comp (rp/reports-app version) (partial http-q/restrict-query-to-environment environment))}))

(defn environments-app
  [version]
  (-> (routes version)
      verify-accepts-json
      wrap-with-paging-options))
