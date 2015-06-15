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
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [slingshot.slingshot :refer [throw+]]))

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

(defn enable-periodic-sync? [remotes]
  (cond
    (or (nil? remotes) (zero? (count remotes)))
    (do
      (log/warn "No remotes specified, sync disabled")
      false)

    (and remotes (> (count remotes) 1))
    (throw+ {:type :puppetlabs.puppetdb.config/configuration-error
             :message "Only a single remote is allowed"})
    :else
    (let [interval (-> remotes first (get :interval ::none))]
      (cond
        (= interval ::none)
        false

        (not (integer? interval))
        (throw+ {:type :puppetlabs.puppetdb.config/configuration-error
                 :message (str "Invalid sync interval: " interval)})

        (neg? interval)
        (throw+ {:type :puppetlabs.puppetdb.config/configuration-error
                 :message (str "Sync interval must be positive or zero: " interval)})

        (zero? interval)
        (do (log/warn "Zero sync interval specified, disabling sync.")
            false)

        :else true))))

(defservice puppetdb-sync-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBCommand submit-command]
   [:PuppetDBServer query shared-globals]]

  (start [this context]
         (let [node-ttl (or (some-> (get-in-config [:node-ttl]) parse-period)
                            Period/ZERO)
               app (->> (sync-app query submit-command node-ttl)
                        (compojure/context (get-route this) []))]
           (add-ring-handler this app)
           (let [remotes (get-in-config [:sync :remotes])]
             (if (enable-periodic-sync? remotes)
               (assoc context
                      :scheduled-sync
                      (atat/interspaced
                       (to-millis (time/seconds (get (first remotes) :interval 120)))
                       #(sync-with! (:endpoint (first remotes)) query submit-command node-ttl)
                       (atat/mk-pool)))
               context))))

  (stop [this context]
        (when-let [s (:scheduled-sync context)]
          (log/info "Stopping pe-puppetdb sync")
          (atat/stop s)
          (log/info "Stopped pe-puppetdb sync"))
        context))
