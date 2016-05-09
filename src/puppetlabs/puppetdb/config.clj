(ns puppetlabs.puppetdb.config
  "Centralized place for reading a user-defined config INI file, validating,
   defaulting and converting into a format that can startup a PuppetDB instance.

   The schemas in this file define what is expected to be present in the INI file
   and the format expected by the rest of the application."
  (:import [java.security KeyStore]
           [org.joda.time Minutes Days Period])
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.trapperkeeper.bootstrap :refer [find-bootstrap-config]]
            [clj-time.core :as time]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]))

(def default-mq-endpoint "puppetlabs.puppetdb.commands")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

;; The config is currently broken into the sections that are defined
;; in the INI file. When the schema defaulting code gets changed to
;; support defaulting/converting nested maps, these configs can be put
;; together in a single schema that defines the config for PuppetDB

(defn warn-unknown-keys
  [schema data]
  (doseq [k (pls/unknown-keys schema data)]
    (log/warnf "The configuration item `%s` does not exist and should be removed from the config." k)))

(defn warn-and-validate
  "Warns a user about unknown configurations items, removes them and validates the config."
  [schema data]
  (warn-unknown-keys schema data)
  (->> (pls/strip-unknown-keys schema data)
       (s/validate schema)))

(defn all-optional
  "Returns a schema map with all the keys made optional"
  [map-schema]
  (kitchensink/mapkeys s/optional-key map-schema))

(def database-config-in
  "Schema for incoming database config (user defined)"
  (all-optional
    {:log-slow-statements (pls/defaulted-maybe s/Int 10)
     :conn-max-age (pls/defaulted-maybe s/Int 60)
     :conn-keep-alive (pls/defaulted-maybe s/Int 45)
     :conn-lifetime (s/maybe s/Int)
     :maximum-pool-size (pls/defaulted-maybe s/Int 25)
     :classname (pls/defaulted-maybe String "org.postgresql.Driver")
     :subprotocol (pls/defaulted-maybe String "postgresql")
     :subname (s/maybe String)
     :username String
     :user String
     :password String
     :syntax_pgs String
     :read-only? (pls/defaulted-maybe String "false")
     :partition-conn-min (pls/defaulted-maybe s/Int 1)
     :partition-conn-max (pls/defaulted-maybe s/Int 25)
     :partition-count (pls/defaulted-maybe s/Int 1)
     :stats (pls/defaulted-maybe String "true")
     :log-statements (pls/defaulted-maybe String "true")
     :statements-cache-size (pls/defaulted-maybe s/Int 0)
     :connection-timeout (pls/defaulted-maybe s/Int 3000)}))

(def write-database-config-in
  "Includes the common database config params, also the write-db specific ones"
  (merge database-config-in
         (all-optional
           {:gc-interval (pls/defaulted-maybe s/Int 60)
            :dlo-compression-interval s/Int
            :report-ttl (pls/defaulted-maybe String "14d")
            :node-purge-ttl (pls/defaulted-maybe String "0s")
            :node-ttl (pls/defaulted-maybe String "0s")})))

(def database-config-out
  "Schema for parsed/processed database config"
  {:classname String
   :subprotocol String
   :subname String
   :log-slow-statements Days
   :conn-max-age Minutes
   :conn-keep-alive Minutes
   :read-only? Boolean
   :partition-conn-min s/Int
   :partition-conn-max s/Int
   :partition-count s/Int
   :stats Boolean
   :log-statements Boolean
   :statements-cache-size s/Int
   :connection-timeout s/Int
   :maximum-pool-size s/Int
   (s/optional-key :conn-lifetime) (s/maybe Minutes)
   (s/optional-key :username) String
   (s/optional-key :user) String
   (s/optional-key :password) String
   (s/optional-key :syntax_pgs) String})

(def write-database-config-out
  "Schema for parsed/processed database config that includes write database params"
  (merge database-config-out
         {:gc-interval Minutes
          (s/optional-key :dlo-compression-interval) Minutes
          :report-ttl Period
          :node-purge-ttl Period
          :node-ttl Period}))

(defn half-the-cores*
  "Function for computing half the cores of the system, useful
   for testing."
  []
  (-> (kitchensink/num-cpus)
      (/ 2)
      int
      (max 1)))

(def half-the-cores
  "Half the number of CPU cores, used for defaulting the number of
   command processors"
  (half-the-cores*))

(defn default-max-command-size
  "Returns the max command size relative to the current max heap. This
  number was reached through testing of large catalogs and 1/205 was
  the largest catalog that could be processed without GC or out of
  memory errors"
  []
  (-> (Runtime/getRuntime)
      .maxMemory
      (/ 205)
      long))

(def command-processing-in
  "Schema for incoming command processing config (user defined) - currently incomplete"
  (all-optional
    {:dlo-compression-threshold (pls/defaulted-maybe String "1d")
     :threads (pls/defaulted-maybe s/Int half-the-cores)
     :store-usage s/Int
     :max-frame-size (pls/defaulted-maybe s/Int 209715200)
     :temp-usage s/Int
     :max-command-size (pls/defaulted-maybe s/Int (default-max-command-size))
     :reject-large-commands (pls/defaulted-maybe String "false")}))

(def command-processing-out
  "Schema for parsed/processed command processing config - currently incomplete"
  {:dlo-compression-threshold Period
   :threads s/Int
   :max-frame-size s/Int
   :max-command-size s/Int
   :reject-large-commands Boolean
   (s/optional-key :store-usage) s/Int
   (s/optional-key :temp-usage) s/Int})

(def puppetdb-config-in
  "Schema for validating the incoming [puppetdb] block"
  (all-optional
    {:certificate-whitelist s/Str
     ;; The `historical-catalogs-limit` setting is only used by `pe-puppetdb`
     :historical-catalogs-limit (pls/defaulted-maybe s/Int 3)
     :disable-update-checking (pls/defaulted-maybe String "false")}))

(def puppetdb-config-out
  "Schema for validating the parsed/processed [puppetdb] block"
  {(s/optional-key :certificate-whitelist) s/Str
   :historical-catalogs-limit s/Int
   :disable-update-checking Boolean})

(def developer-config-in
  (all-optional
    {:pretty-print (pls/defaulted-maybe String "false")}))

(def developer-config-out
  {:pretty-print Boolean})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database config

(defn validate-db-settings
  "Throws a {:type ::cli-error :message m} exception
  describing the required additions if the [database] configuration
  doesn't specify classname, subprotocol and subname, all of which are
  now required."
  [{db-config :database :or {db-config {}} :as config}]
  (when (str/blank? (:subname db-config))
    (throw+
     {:type ::cli-error
      :message
      (str "PuppetDB requires PostgreSQL."
           "  The [database] section must contain an appropriate"
           " \"//host:port/database\" subname setting.")}))
  config)

(defn convert-section-config
  "validates and converts a `section-config` to `section-schema-out` using defaults from `section-schema-in`."
  [section-schema-in section-schema-out section-config]
  (->> section-config
       (warn-and-validate section-schema-in)
       (pls/defaulted-data section-schema-in)
       (pls/convert-to-schema section-schema-out)))

(defn configure-section
  [config section section-schema-in section-schema-out]
  (->> (get config section {})
       (convert-section-config section-schema-in section-schema-out)
       (s/validate section-schema-out)
       (assoc config section)))

(defn configure-read-db
  "Wrapper for configuring the read-database section of the config.
  We rely on the fact that the database section has already been configured
  using `configure-section`."
  [{:keys [database read-database] :as config}]
  (if read-database
    (configure-section config :read-database database-config-in database-config-out)
    (->> (assoc database :read-only? true)
         (pls/strip-unknown-keys database-config-out)
         (s/validate database-config-out)
         (assoc config :read-database))))

(defn configure-puppetdb
  "Validates the [puppetdb] section of the config"
  [{:keys [puppetdb] :as config :or {puppetdb {}}}]
  (configure-section config :puppetdb puppetdb-config-in puppetdb-config-out))

(defn configure-developer
  [{:keys [developer] :as config :or {developer {}}}]
  (configure-section config :developer developer-config-in developer-config-out))

(defn configure-command-processing
  [config]
  (configure-section config :command-processing command-processing-in command-processing-out))

(defn convert-config
  "Given a `config` map (created from the user defined config), validate, default and convert it
   to the internal Clojure format that PuppetDB expects"
  [config]
  (-> config
      (configure-section :database write-database-config-in write-database-config-out)
      (update :database #(utils/assoc-when % :dlo-compression-interval (:gc-interval %)))
      configure-read-db
      configure-command-processing
      configure-puppetdb))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Global Config

(defn validate-vardir
  "Checks that `vardir` is specified, exists, and is writeable, throwing
  appropriate exceptions if any condition is unmet."
  [config]
  (let [vardir (some-> (get-in config [:global :vardir])
                       io/file
                       kitchensink/absolute-path
                       fs/file)]
    (cond
     (nil? vardir)
     (throw (IllegalArgumentException.
             "Required setting 'vardir' is not specified. Please set it to a writable directory."))

     (not (.isAbsolute vardir))
     (throw (IllegalArgumentException.
             (format "Vardir %s must be an absolute path." vardir)))

     (not (.exists vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s does not exist. Please create it and ensure it is writable." vardir)))

     (not (.isDirectory vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not a directory." vardir)))

     (not (.canWrite vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not writable." vardir)))

     :else
     config)))

(defn normalize-product-name
  "Checks that `product-name` is specified as a legal value, throwing an
  exception if not. Returns `product-name` if it's okay."
  [product-name]
  {:pre [(string? product-name)]
   :post [(= (str/lower-case product-name) %)]}
  (let [lower-product-name (str/lower-case product-name)]
    (when-not (#{"puppetdb" "pe-puppetdb"} lower-product-name)
      (throw (IllegalArgumentException.
              (format "product-name %s is illegal; either puppetdb or pe-puppetdb are allowed" product-name))))
    lower-product-name))

(defn configure-globals
  "Configures the global properties from the user defined config"
  [config]
  (update config :global
          #(-> %
               (utils/assoc-when :product-name "puppetdb")
               (update :product-name normalize-product-name)
               (utils/assoc-when :update-server "http://updates.puppetlabs.com/check-for-updates"))))

(defn warn-retirements
  "Warns about configuration retirements.  Abruptly exits the entire
  process if a [global] url-prefix is found."
  [config-data]
  (doseq [param [:classname :subprotocol]]
    (when (get-in config-data [:database param])
      (utils/println-err
       (format "The [database] %s setting has been retired and will be ignored."
               (name param)))))
  (when (get-in config-data [:global :catalog-hash-conflict-debugging])
    (utils/println-err (str "The configuration item `catalog-hash-conflict-debugging`"
                            " in the [global] section is retired,"
                            " please remove this item from your config."
                            " Consult the documentation for more details.")))
  (when (get-in config-data [:repl])
    (utils/println-err (str "The configuration block [repl] is now retired and will be ignored."
                            " Use [nrepl] instead. Consult the documentation for more details.")))
  (when (get-in config-data [:global :url-prefix])
    (utils/println-err (str "The configuration item `url-prefix` in the [global] section is retired,"
                            " please remove this item from your config."
                            " PuppetDB has a non-configurable context route of `/pdb`."
                            " Consult the documentation for more details."))
    (flush)
    (binding [*out* *err*] (flush))
    (System/exit 1)) ; cf. PDB-2053
  config-data)

(defn add-web-routing-service-config
  [config-data]
  (let [default-web-router-service {:puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-webservice "/metrics"
                                    :puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"
                                    :puppetlabs.puppetdb.pdb-routing/pdb-routing-service "/pdb"}
        bootstrap-cfg (-> (find-bootstrap-config config-data)
                          slurp
                          str/split-lines)
        dashboard-redirect? (contains? (set bootstrap-cfg)
                                       "puppetlabs.puppetdb.dashboard/dashboard-redirect-service")]
    (doseq [[svc route] default-web-router-service]
      (when (get-in config-data [:web-router-service svc])
        (-> (format "Configuration of the `%s` route is not allowed. This setting defaults to `%s`." svc route)
            utils/println-err)))
    ;; We override the users settings as to make the above routes *not* configurable
    (-> config-data
        (assoc-in [:metrics :reporters :jmx :enabled] true)
        (update :web-router-service merge default-web-router-service
                (when dashboard-redirect?
                  {:puppetlabs.puppetdb.dashboard/dashboard-redirect-service "/"})))))

(defn- add-mq-defaults
  [config-data]
  (-> config-data
      (update-in [:command-processing :mq :address]
                 #(or % "vm://localhost?jms.prefetchPolicy.all=1&create=false"))
      (update-in [:command-processing :mq :endpoint]
                 #(or % default-mq-endpoint))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(def adjust-and-validate-tk-config
  (comp add-web-routing-service-config
        warn-retirements
        validate-db-settings))

(defn hook-tk-parse-config-data
  "This is a robert.hooke compatible hook that is designed to intercept
   trapperkeeper configuration before it is used, so that we may munge &
   customize it.  It may throw {:type ::cli-error :message m}."
  [f args]
  (adjust-and-validate-tk-config (f args)))

(defn process-config!
  "Accepts a map containing all of the user-provided configuration values
  and configures the various PuppetDB subsystems."
  [config]
  (-> config
      configure-globals
      configure-developer
      validate-vardir
      convert-config
      add-mq-defaults))

(defn foss? [config]
  (= "puppetdb" (get-in config [:global :product-name])))

(defn pe? [config]
  (= "pe-puppetdb" (get-in config [:global :product-name])))

(defn update-server [config]
  (get-in config [:global :update-server]))

(defn mq-endpoint [config]
  (get-in config [:command-processing :mq :endpoint]))

(defn mq-broker-url
  "Returns an appropriate ActiveMQ broker URL."
  [config]
  (format "%s&wireFormat.maxFrameSize=%s&marshal=true"
          (get-in config [:command-processing :mq :address])
          (get-in config [:command-processing :max-frame-size])))

(defn mq-thread-count
  "Returns the desired number of MQ listener threads."
  [config]
  (get-in config [:command-processing :threads]))

(defn reject-large-commands?
  [config]
  (get-in config [:command-processing :reject-large-commands]))

(defn max-command-size
  [config]
  (get-in config [:command-processing :max-command-size]))

(defn mq-dir [config]
  (str (io/file (get-in config [:global :vardir]) "mq")))

(defn mq-discard-dir [config]
  (str (io/file (mq-dir config) "discard")))

(defprotocol DefaultedConfig
  (get-config [this]))

(defn create-defaulted-config-service [config-transform-fn]
  (tk/service
   DefaultedConfig
   [[:ConfigService get-config]]
   (init [this context]
         (assoc context :config (config-transform-fn (get-config))))
   (get-config [this]
               (:config (service-context this)))))

(def config-service
  (create-defaulted-config-service process-config!))
