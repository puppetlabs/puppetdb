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
  [{:keys [api-version scf-read-db url-prefix] :as globals}]
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
             (validate-query-params {:optional (cons "query" paging/query-params)}))}

   [environment]
   {:get (-> (constantly
              (environment-status api-version environment scf-read-db))
             (validate-query-params {}))}

   [environment "facts" &]
   (-> (comp (f/facts-app globals) (partial http-q/restrict-query-to-environment environment))
       (wrap-with-parent-check globals :environment environment))

   [environment "resources" &]
   (-> (comp (r/resources-app globals) (partial http-q/restrict-query-to-environment environment))
       (wrap-with-parent-check globals :environment environment))

   [environment "events" &]
   (-> (comp (ev/events-app globals) (partial http-q/restrict-query-to-environment environment))
       (wrap-with-parent-check globals :environment environment))

   [environment "reports" &]
   (-> (comp (rp/reports-app globals) (partial http-q/restrict-query-to-environment environment))
       (wrap-with-parent-check globals :environment environment))))

(defn environments-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      wrap-with-paging-options))
