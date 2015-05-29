(ns puppetlabs.puppetdb.http.environments
  (:require [puppetlabs.puppetdb.query.environments :as e]
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
  [version environment db]
  (if-let [status (with-transacted-connection db
                    (e/status version environment))]
    (http/json-response status)
    (http/json-response {:error (str "No information is known about " environment)} http/status-not-found)))

(defn routes
  [globals]
  (let [{:keys [api-version url-prefix scf-read-db]} globals]
    (app
     []
     {:get (-> (fn [{:keys [params paging-options]}]
                 (produce-streaming-body
                  :environments
                  api-version
                  (params "query")
                  paging-options
                  scf-read-db
                  url-prefix))
               (validate-query-params
                {:optional (cons "query" paging/query-params)}))}

     [environment]
     {:get (-> (constantly (environment-status api-version environment scf-read-db))
               ;; Being a singular item, querying and pagination don't really make
               ;; sense here
               (validate-query-params {}))}

     [environment "facts" &]
     {:get (comp (f/facts-app globals)
                 (partial http-q/restrict-query-to-environment environment))}

     [environment "resources" &]
     {:get (comp (r/resources-app globals)
                 (partial http-q/restrict-query-to-environment environment))}

     [environment "events" &]
     {:get (comp (ev/events-app globals)
                 (partial http-q/restrict-query-to-environment environment))}

     [environment "reports" &]
     {:get (comp (rp/reports-app globals)
                 (partial http-q/restrict-query-to-environment environment))})))

(defn environments-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      wrap-with-paging-options))
