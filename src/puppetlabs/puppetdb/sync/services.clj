(ns puppetlabs.puppetdb.sync.services
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [overtone.at-at :as atat]
            [puppetlabs.puppetdb.sync.command :as command]
            [puppetlabs.puppetdb.time :refer [to-millis]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.sync.command :refer [sync-from-remote!]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [get-service]]
            [puppetlabs.puppetdb.utils :as utils]
            [compojure.core :refer [routes POST context]]
            [schema.core :as s]))

(defn- sync-with! [host query-fn submit-command-fn]
  (try
    (sync-from-remote! query-fn submit-command-fn host)
    (catch Exception ex
      (log/error ex (format "Remote sync from %s failed" (pr-str host))))))

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
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler]
   [:PuppetDBServer query shared-globals submit-command]]

  (start [this context]
    (add-ring-handler this (sync-app query submit-command))
    (let [remotes (get-in-config [:sync :remotes])]
      (when (and remotes (> (count remotes) 1))
        (throw (IllegalArgumentException. "Only a single remote allowed")))
      (assoc context
             :scheduled-sync
             (when remotes
               (atat/interspaced
                (to-millis (time/seconds (get (first remotes) :interval 120)))
                #(sync-with! (:endpoint (remotes 0)) query submit-command)
                (atat/mk-pool))))))

  (stop [this context]
    (when-let [s (:scheduled-sync context)]
      (log/info "Stopping pe-puppetdb sync")
      (atat/stop s)
      (log/info "Stopped pe-puppetdb sync"))
    context))
