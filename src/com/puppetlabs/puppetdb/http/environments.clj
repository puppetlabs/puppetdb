(ns com.puppetlabs.puppetdb.http.environments
  (:require [com.puppetlabs.puppetdb.query.environments :as e]
            [com.puppetlabs.puppetdb.http :as http]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json wrap-read-db-tx]]
            [com.puppetlabs.puppetdb.http.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.http.resources :as r]
            [com.puppetlabs.puppetdb.http.events :as ev]
            [com.puppetlabs.puppetdb.http.reports :as rp]))

(defn routes
  [version]
  (app
   wrap-read-db-tx
   []
   {:get (fn [req]
           (http/query-result-response (e/all-environments)))}

   [environment]
   {:get (fn [req]
           (http/query-result-response (e/query-environment environment)))}

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
  (case version
    (:v2 :v3) (throw (IllegalArgumentException. (str "Environment queries not supported on " (name version))))
    (verify-accepts-json (routes version))))
