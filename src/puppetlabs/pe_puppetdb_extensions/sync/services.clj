(ns puppetlabs.pe-puppetdb-extensions.sync.services
  (:import [org.joda.time Period])
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [overtone.at-at :as atat]
            [puppetlabs.puppetdb.time :refer [to-millis]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote!]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [get-service]]
            [puppetlabs.puppetdb.utils :as utils]
            [compojure.core :refer [routes POST] :as compojure]
            [schema.core :as s]
            [clj-time.coerce :refer [to-date-time]]
            [puppetlabs.puppetdb.time :refer [parse-period]]))

(defn- sync-with! [host query-fn submit-command-fn node-ttl]
  (try
    (sync-from-remote! query-fn submit-command-fn host node-ttl)
    (catch Exception ex
      (log/errorf ex "Remote sync from %s failed" (pr-str host)))))

(def sync-request-schema {:remote_host_path s/Str})

(defn sync-app
  "Top level route for PuppetDB sync"
  [query-fn submit-command-fn node-ttl]
  (routes
   (POST "/v1/trigger-sync" {:keys [body]}
         (let [sync-request (json/parse-string (slurp body) true)]
           (s/validate sync-request-schema sync-request)
           (sync-from-remote! query-fn submit-command-fn (:remote_host_path sync-request) node-ttl)
           {:status 200 :body "success"}))))

(defservice puppetdb-sync-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer query shared-globals submit-command]]

  (start [this context]
         (let [node-ttl (or (some-> (get-in-config [:node-ttl]) parse-period)
                            Period/ZERO)
               app (->> (sync-app query submit-command node-ttl)
                        (compojure/context (get-route this) []))]
           (add-ring-handler this app)
           (let [remotes (get-in-config [:sync :remotes])]
             (when (and remotes (> (count remotes) 1))
               (throw (IllegalArgumentException. "Only a single remote is allowed")))
             (assoc context
                    :scheduled-sync
                    (when remotes
                      (atat/interspaced
                       (to-millis (time/seconds (get (first remotes) :interval 120)))
                       #(sync-with! (:endpoint (remotes 0)) query submit-command node-ttl)
                       (atat/mk-pool)))))))

  (stop [this context]
        (when-let [s (:scheduled-sync context)]
          (log/info "Stopping pe-puppetdb sync")
          (atat/stop s)
          (log/info "Stopped pe-puppetdb sync"))
        context))
