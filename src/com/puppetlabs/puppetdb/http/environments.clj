(ns com.puppetlabs.puppetdb.http.environments
  (:require [com.puppetlabs.puppetdb.query.environments :as e]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params wrap-with-paging-options]]
            [com.puppetlabs.puppetdb.http.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.http.resources :as r]
            [com.puppetlabs.puppetdb.http.events :as ev]
            [com.puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [com.puppetlabs.puppetdb.http.reports :as rp]
            [com.puppetlabs.jdbc :refer [with-transacted-connection get-result-count]]
            [com.puppetlabs.cheshire :as json]))

(defn environment-status
  "Produce a response body for a single environment."
  [version environment db]
  (if-let [status (with-transacted-connection db
                    (e/status version environment))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " environment)} pl-http/status-not-found)))

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
             (:scf-read-db globals)))
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
