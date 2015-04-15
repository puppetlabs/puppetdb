(ns puppetlabs.puppetdb.sync.services
  (:require [puppetlabs.puppetdb.sync.command :as command]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [get-service]]
            [puppetlabs.puppetdb.utils :as utils]
            [compojure.core :refer [routes POST context]]
            [schema.core :as s]))

(def sync-request-schema {:remote_host_path s/Str})

(defn sync-app
  "Top level route for PuppetDB sync"
  [query-fn submit-command-fn]
  (context "/sync" []
           (routes
            (POST "/v1/trigger-sync" {:keys [body]}
                  (let [sync-request (json/parse-string (slurp body) true)]
                    (s/validate sync-request-schema sync-request)
                    (command/sync-from-remote! query-fn submit-command-fn (:remote_host_path sync-request))
                    {:status 200 :body "success"})))))

(defservice puppetdb-sync-service
  [[:WebroutingService add-ring-handler]
   [:PuppetDBServer query shared-globals submit-command]]
  (start [this context]
         (add-ring-handler this (sync-app query submit-command))
         context))
