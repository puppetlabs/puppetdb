(ns puppetlabs.pe-puppetdb-extensions.config
  (:require
   [clojure.core.match :as cm]
   [clojure.tools.logging :as log]
   [com.rpl.specter :as sp]
   [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote!]]
   [puppetlabs.puppetdb.config :as conf]
   [puppetlabs.puppetdb.time :refer [parse-period period?]]
   [puppetlabs.puppetdb.utils :as utils :refer [throw+-cli-error!]]))

(defn- coerce-to-hocon-style-config
  "Convert ini-style configuration structure to hocon-style, or just pass a
  config through if it's already hocon-style."
  [sync-config]
  (cm/match [sync-config]
    [{:remotes _}]
    sync-config

    [({} :guard empty?)]
    {:remotes []}

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

(defn- check-sync-config-for-extra-keys! [sync-config]
  (if (not= [:remotes] (keys sync-config))
    (throw+-cli-error! "The 'sync' config section may only contain a 'remotes' key.")
    (do
     (doseq [remote (:remotes sync-config)]
       (if (not= #{:server_url :interval} (set (keys remote)))
         (throw+-cli-error! "Each remote must contain a 'server_url' and 'interval' key, and no more.")))
     sync-config)))

(defn- try-parse-period [x]
  (try
    (parse-period x)
    (catch Exception _
      nil)))

(defn- coerce-to-period [x]
  (cond
    (period? x) x
    (string? x) (try-parse-period x)
    :else nil))

(defn- parse-sync-config-intervals [sync-config]
  (sp/transform [:remotes sp/ALL :interval]
                #(or (coerce-to-period %)
                     (throw+-cli-error! (format "Interval '%s' cannot be parsed as a time period. Did you mean '%ss?'" % %)))
                sync-config))

(defn- uri-has-port [uri]
  (not= -1 (.getPort uri)))

(defn- uri-with-port [uri port]
  (java.net.URI. (.getScheme uri)
                 (.getUserInfo uri)
                 (.getHost uri)
                 port
                 (.getPath uri)
                 (.getQuery uri)
                 (.getFragment uri)))

(defn- set-default-ports
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

(defn- fixup-remotes [sync-config]
  (-> sync-config
      coerce-to-hocon-style-config
      check-sync-config-for-extra-keys!
      parse-sync-config-intervals
      set-default-ports))

(defn adjust-for-extensions [config]
  (update config :sync
          (fn [prev]
            (if-let [unsafe? (:allow-unsafe-sync-triggers prev)]
              (do
                (log/warn "Allowing unsafe sync triggers")
                (assoc (fixup-remotes (dissoc prev :allow-unsafe-sync-triggers))
                       :allow-unsafe-sync-triggers unsafe?))
              (fixup-remotes prev)))))

(def config-service
  (conf/create-defaulted-config-service (comp adjust-for-extensions
                                              conf/process-config!)))
