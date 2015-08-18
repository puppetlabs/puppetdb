(ns puppetlabs.pe-puppetdb-extensions.sync.services
  (:import [org.joda.time Period])
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [overtone.at-at :as atat]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pe-puppetdb-extensions.semlog :as semlog]
            [puppetlabs.puppetdb.time :refer [to-millis periods-equal? parse-period period?]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote! with-trailing-slash]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [get-service]]
            [puppetlabs.puppetdb.utils :as utils :refer [throw+-cli-error!]]
            [compojure.core :refer [routes POST] :as compojure]
            [schema.core :as s]
            [clj-time.coerce :refer [to-date-time]]
            [slingshot.slingshot :refer [throw+]]
            [clojure.core.match :as m]
            [com.rpl.specter :as sp]))

(def currently-syncing (atom false))

(def sync-request-schema {:remote_host_path s/Str})

(defn- query-endpoint
  "Given a base server-url, but the query endpoint onto the end of it. Don't do
  this if it looks like one is already there. "
  [^String server-url]
  (if (.contains server-url "/v4")
    server-url
    (str (with-trailing-slash server-url) "pdb/query/v4")))

(defn make-remote-server
  "Create a remote-server structure out the given url, pulling ssl options from
  jetty-config."
  [server-url jetty-config]
  (-> (select-keys jetty-config [:ssl-cert :ssl-key :ssl-ca-cert])
      (assoc :url (query-endpoint server-url))))

(defn- sync-with! [remote-server query-fn submit-command-fn node-ttl]
  (if (compare-and-set! currently-syncing false true)
    (try (sync-from-remote! query-fn submit-command-fn remote-server node-ttl)
         {:status 200 :body "success"}
         (catch Exception ex
           (let [err "Remote sync from %s failed"
                 url (:url remote-server)]
             (semlog/maplog [:sync :error] ex
                            {:remote url :phase "sync"}
                            (format err url))
             (log/error ex err url)
             {:status 200 :body (format err url)}))
         (finally (swap! currently-syncing (constantly false))))
    (let [err "Refusing to sync from %s. Sync already in progress."
          url (:url remote-server)]
      (semlog/maplog [:sync :info]
                     {:remote url :phase "sync"}
                     (format err url))
      (log/infof err url)
      {:status 200 :body (format err url)})))

(defn sync-app
  "Top level route for PuppetDB sync"
  [query-fn submit-command-fn node-ttl jetty-config validate-sync-fn]
  (routes
    (POST "/v1/trigger-sync" {:keys [body]}
          (let [sync-request (json/parse-string (slurp body) true)
                remote-url (:remote_host_path sync-request)]
            (s/validate sync-request-schema sync-request)
            (let [remote-server (make-remote-server remote-url jetty-config)]
              (if (validate-sync-fn remote-server)
                (sync-with! remote-server query-fn submit-command-fn node-ttl)
                {:status 503 :body (format "Refusing to sync. PuppetDB is not configured to sync with %s" remote-url)}))))))

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
  (sp/transform [:remotes sp/ALL :interval]
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
  (sp/transform [:remotes sp/ALL :server_url]
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

(defn- scrub-sync-config
  "A utility function for tests which will dissoc the
  `:allow-unsafe-sync-triggers' config option and ensure the sync-config map is
  either non-empty or nil, which is helpful for our uses core.match above"
  [sync-config]
  (let [sync-config-scrubbed (-> sync-config
                                 (dissoc :allow-unsafe-sync-triggers))]
    (when (seq sync-config-scrubbed)
      sync-config-scrubbed)))

(defn validate-trigger-sync
  "Validates `remote-server' as a valid sync target given user config items"
  [allow-unsafe-sync-triggers remotes-config jetty-config remote-server]
  (let [valid-remotes (map (comp #(make-remote-server % jetty-config) :server_url) remotes-config)]
    (or allow-unsafe-sync-triggers
        (ks/seq-contains? valid-remotes remote-server))))

(defservice puppetdb-sync-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBCommand submit-command]
   [:PuppetDBServer query shared-globals]]

  (start [this context]
         (let [{node-ttl :node-ttl, sync-config :sync, jetty-config :jetty} (get-config)
               node-ttl (or (some-> node-ttl parse-period)
                            Period/ZERO)
               allow-unsafe-sync-triggers (:allow-unsafe-sync-triggers sync-config false)
               remotes-config (-> sync-config
                                  scrub-sync-config
                                  extract-and-check-remotes-config)
               validate-sync-fn (partial validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config)]
           (->> (sync-app query submit-command node-ttl jetty-config validate-sync-fn)
                (compojure/context (get-route this) [])
                (add-ring-handler this))
           (if (enable-periodic-sync? remotes-config)
             (let [{:keys [interval server_url]} (first remotes-config)
                   remote-server (make-remote-server server_url jetty-config)]
               (assoc context
                      :scheduled-sync
                      (atat/interspaced (to-millis interval)
                                        #(sync-with! remote-server query submit-command node-ttl)
                                        (atat/mk-pool))))
             context)))

  (stop [this context]
        (when-let [s (:scheduled-sync context)]
          (log/info "Stopping pe-puppetdb sync")
          (atat/stop s)
          (log/info "Stopped pe-puppetdb sync"))
        context))
