(ns puppetlabs.pe-puppetdb-extensions.sync.services
  (:import [org.joda.time Period])
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [overtone.at-at :as atat]
            [puppetlabs.puppetdb.time :refer [to-millis periods-equal? parse-period period?]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote!]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [get-service]]
            [puppetlabs.puppetdb.utils :as utils :refer [throw+-cli-error!]]
            [compojure.core :refer [routes POST] :as compojure]
            [schema.core :as s]
            [clj-time.coerce :refer [to-date-time]]
            [slingshot.slingshot :refer [throw+]]
            [clojure.core.match :as m]
            [com.rpl.specter :as sp]))


(defn- query-endpoint [server-url]
  (let [top-uri (java.net.URI. server-url)]
    (str (.resolve top-uri "/pdb/query/v4"))))

(defn- sync-with! [server-url query-fn submit-command-fn node-ttl]
  (let [endpoint (query-endpoint server-url)]
   (try
     (sync-from-remote! query-fn submit-command-fn endpoint node-ttl)
     (catch Exception ex
       (log/errorf ex "Remote sync from %s failed" endpoint)))))

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
    (throw+-cli-error! "Only a single remote is allowed")

    :else
    (let [interval (-> remotes first (get :interval ::none))]
      (cond
        (= interval ::none)
        false

        (not (period? interval))
        (throw+-cli-error! (format "Specified sync interval %s does not appear to be a time period" interval))

        (neg? (time/in-millis interval))
        (throw+-cli-error! (str "Sync interval must be positive or zero: " interval))

        (periods-equal? interval Period/ZERO)
        (do (log/warn "Zero sync interval specified, disabling sync.")
            false)

        :else true))))

(defn coerce-to-hocon-style-config
  "Convert ini-style configuration structure to hocon-style, or just pass a
  config through if it's already hocon-style."
  [sync-config]
  (m/match [sync-config]
    [{:remotes _}]
    sync-config

    [nil]
    {:remotes []}

    [({:server_urls server_urls, :intervals intervals} :only [:server_urls :intervals])]
    (let [server_urls (clojure.string/split server_urls #",")
          intervals (clojure.string/split intervals #",")]
      (when-not (= (count server_urls) (count intervals))
        (throw+-cli-error! "The sync configuration must contain the same number of server_urls and intervals"))
      {:remotes (map #(hash-map :server_url %1, :interval %2)
                     server_urls
                     intervals)})

    [{:server_urls _}]
    (throw+-cli-error! "When specifying server_urls in the sync config, you must also provide an 'intervals' entry.")

    [{:intervals _}]
    (throw+-cli-error! "When specifying intervals in the sync config, you must also provide a 'sync_urls' entry.")

    :else
    (throw+-cli-error! "The [sync] section must contain settings for server_urls and intervals, and no more.")))

(defn check-sync-config-for-extra-keys! [sync-config]
  (if (not= [:remotes] (keys sync-config))
    (throw+-cli-error! "The 'sync' config section may only contain a 'remotes' key.")

    (do
     (doseq [remote (:remotes sync-config)]
       (if (not= #{:server_url :interval} (set (keys remote)))
         (throw+-cli-error! "Each remote must contain a 'server_url' and 'interval' key, and no more.")))
     sync-config)))

(defn try-parse-period [x]
  (try
    (parse-period x)
    (catch Exception _
      nil)))

(defn coerce-to-period [x]
  (cond
    (period? x) x
    (string? x) (try-parse-period x)
    :else nil))

(defn parse-sync-config-intervals [sync-config]
   (sp/update [:remotes sp/ALL :interval]
              #(or (coerce-to-period %)
                   (throw+-cli-error! (format "Interval '%s' cannot be parsed as a time period. Did you mean '%ss?'" % %)))
              sync-config))

(defn uri-has-port [uri]
  (not= -1 (.getPort uri)))

(defn uri-with-port [uri port]
  (java.net.URI. (.getScheme uri)
                 (.getUserInfo uri)
                 (.getHost uri)
                 port
                 (.getPath uri)
                 (.getQuery uri)
                 (.getFragment uri)))

(defn set-default-ports
  "Use port 8081 for all server_urls that haven't specified one."
  [sync-config]
  (sp/update [:remotes sp/ALL :server_url]
             (fn [server_url]
               (let [uri (java.net.URI. server_url)]
                 (if (uri-has-port uri)
                   server_url
                   (case (.getScheme uri)
                     "https" (str (uri-with-port uri 8081))
                     "http"  (str (uri-with-port uri 8080))
                     server_url))))
             sync-config))

(defn extract-and-check-remotes-config [sync-config]
  (-> sync-config
      coerce-to-hocon-style-config
      check-sync-config-for-extra-keys!
      parse-sync-config-intervals
      set-default-ports
      :remotes))

(defservice puppetdb-sync-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBCommand submit-command]
   [:PuppetDBServer query shared-globals]]

  (start [this context]
         (let [{node-ttl :node-ttl, sync-config :sync} (get-config)
               node-ttl (or (some-> node-ttl parse-period)
                            Period/ZERO)
               remotes-config (extract-and-check-remotes-config sync-config)
               app (->> (sync-app query submit-command node-ttl)
                        (compojure/context (get-route this) []))]
           (add-ring-handler this app)
           (if (enable-periodic-sync? remotes-config)
             (let [{:keys [interval server_url]} (first remotes-config)]
               (assoc context
                      :scheduled-sync
                      (atat/interspaced (to-millis interval)
                                        #(sync-with! server_url query submit-command node-ttl)
                                        (atat/mk-pool))))
             context)))

  (stop [this context]
        (when-let [s (:scheduled-sync context)]
          (log/info "Stopping pe-puppetdb sync")
          (atat/stop s)
          (log/info "Stopped pe-puppetdb sync"))
        context))
