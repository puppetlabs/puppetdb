(ns puppetlabs.pe-puppetdb-extensions.config
  (:require
   [clojure.core.match :as cm]
   [clojure.tools.logging :as log]
   [com.rpl.specter :as sp]
   [slingshot.slingshot :refer [try+ throw+]]
   [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote!]]
   [puppetlabs.puppetdb.config :as conf]
   [puppetlabs.puppetdb.time :refer [parse-period period?]]
   [puppetlabs.puppetdb.utils :as utils :refer [throw+-cli-error!]]
   [puppetlabs.puppetdb.schema :refer [defn-validated]]
   [schema.core :as s]
   [schema.coerce :as sc]
   [puppetlabs.puppetdb.zip :as zip]
   [clojure.walk :as walk])
  (:import
   [org.joda.time Period ReadablePeriod PeriodType DateTime]
   [java.net URI]))

(defn- parse-list [s]
  (clojure.string/split s #","))

(def sync-config-ini-schema
  (->
   {(s/optional-key :server-urls) s/Str
    (s/optional-key :intervals) s/Str
    (s/optional-key :allow-unsafe-sync-triggers) s/Bool
    (s/optional-key :allow-unsafe-cleartext-sync) s/Bool}
   (s/constrained (fn [{:keys [server-urls intervals]}]
                    (= (nil? server-urls) (nil? intervals)))
                  "server_urls and intervals must either both exist or both be absent")
   (s/constrained (fn [{:keys [server-urls intervals] :as x}]
                    (if (and server-urls intervals)
                      (= (count (parse-list server-urls)) (count (parse-list intervals)))
                      true))
                  "server_urls and intervals must have the same number of entries")))


(defn- string->period [s]
  (if (string? s) (parse-period s) s))

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

(defn- string->uri [s]
  (let [uri (if (string? s) (URI. s) s)]
    (if (and (instance? URI uri) (not (uri-has-port uri)))
      (case (.getScheme uri)
        "https" (uri-with-port uri 8081)
        "http" (uri-with-port uri 8080)
        uri)
      uri)))

(def sync-config-coercion-matcher
  {Period string->period
   URI string->uri})

(def sync-config-schema
  (-> {:remotes [{:server-url URI
                  :interval Period}]
       (s/optional-key :allow-unsafe-sync-triggers) s/Bool
       (s/optional-key :allow-unsafe-cleartext-sync) s/Bool}
      (s/constrained (fn [{:keys [remotes allow-unsafe-cleartext-sync]}]
                       (or allow-unsafe-cleartext-sync
                           (every? #(= "https" (.getScheme (:server-url %)))
                                   remotes)))
                     (str "Only https urls are allowed unless "
                          "allow_unsafe_cleartext_sync is set"))))

(def sync-config-coercer (sc/coercer! sync-config-schema sync-config-coercion-matcher))

(defn coerce-to-hocon-style-config
  "Convert ini-style configuration structure to hocon-style, or just pass a
   config through if it's already hocon-style."
  [sync-config]
  (cond
    (nil? sync-config)
    {:remotes []}

    (:remotes sync-config)
    ;; already hocon-style
    (sync-config-coercer sync-config)

    :else
    (-> (s/validate sync-config-ini-schema sync-config)
        (dissoc :server-urls :intervals)
        (assoc :remotes (if (and (:server-urls sync-config)
                                 (:intervals sync-config))
                          (map #(hash-map :server-url %1, :interval %2)
                               (parse-list (:server-urls sync-config ))
                               (parse-list (:intervals sync-config)))
                          []))
        sync-config-coercer)))

(defn- recursive-underscore->dash-keys
  [m]
  (:node (zip/post-order-transform
           (zip/tree-zipper m)
           [(fn [node]
              (when (map-entry? node)
                (update node 0 utils/underscores->dashes)))])))

(defn- parse-sync-config [sync-config]
  (try+
   (let [parsed-config (-> sync-config
                           recursive-underscore->dash-keys
                           coerce-to-hocon-style-config)]
     (when (:allow-unsafe-sync-triggers parsed-config)
       (log/warn "Allowing unsafe sync triggers"))
     (when (:allow-unsafe-cleartext-sync parsed-config)
       (log/warn "Allowing unsafe cleartext sync"))
     parsed-config)
   (catch [:type :schema.core/error] ex
     (throw+ (merge ex {:type ::utils/cli-error
                        :message (str "Invalid sync config: " ex)})))))

(def config-service
  (conf/create-defaulted-config-service
   (fn [config]
     (-> config
         conf/process-config!
         (update :sync parse-sync-config)))))
