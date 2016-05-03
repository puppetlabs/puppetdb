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
            [puppetlabs.puppetdb.utils :as utils]))

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
     :classname (s/maybe String)
     :subprotocol (s/maybe String)
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
     :statements-cache-size (pls/defaulted-maybe s/Int 1000)
     :connection-timeout (pls/defaulted-maybe s/Int 500)}))

(def write-database-config-in
  "Includes the common database config params, also the write-db specific ones"
  (merge database-config-in
         (all-optional
           {:gc-interval (pls/defaulted-maybe s/Int 60)
            :report-ttl (pls/defaulted-maybe String "14d")
            :node-purge-ttl (pls/defaulted-maybe String "0s")
            :node-ttl (pls/defaulted-maybe String "0s")
            :connection-timeout (pls/defaulted-maybe s/Int 1000)})))

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
   (s/optional-key :conn-lifetime) (s/maybe Minutes)
   (s/optional-key :username) String
   (s/optional-key :user) String
   (s/optional-key :password) String
   (s/optional-key :syntax_pgs) String})

(def write-database-config-out
  "Schema for parsed/processed database config that includes write database params"
  (merge database-config-out
         {:gc-interval Minutes
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

(def command-processing-in
  "Schema for incoming command processing config (user defined) - currently incomplete"
  (all-optional
    {:dlo-compression-threshold (pls/defaulted-maybe String "1d")
     :threads (pls/defaulted-maybe s/Int half-the-cores)
     :store-usage s/Int
     :max-frame-size (pls/defaulted-maybe s/Int 209715200)
     :memory-usage s/Int
     :temp-usage s/Int}))

(def command-processing-out
  "Schema for parsed/processed command processing config - currently incomplete"
  {:dlo-compression-threshold Period
   :threads s/Int
   :max-frame-size s/Int
   (s/optional-key :memory-usage) s/Int
   (s/optional-key :store-usage) s/Int
   (s/optional-key :temp-usage) s/Int})

(def puppetdb-config-in
  "Schema for validating the incoming [puppetdb] block"
  (all-optional
    {:certificate-whitelist s/Str
     :disable-update-checking (pls/defaulted-maybe String "false")}))

(def puppetdb-config-out
  "Schema for validating the parsed/processed [puppetdb] block"
  {(s/optional-key :certificate-whitelist) s/Str
   :disable-update-checking Boolean})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database config

(defn hsql-default-connection
  "Returns a map of default, file-backed, HyperSQL connection information"
  [vardir]
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (format "file:%s;hsqldb.tx=mvcc;sql.syntax_pgs=true" (io/file vardir "db"))})

(defn validate-db-settings
  "Throws a {:type ::cli-error :message m} exception
  describing the required additions if the [database] configuration
  doesn't specify classname, subprotocol and subname, all of which are
  now required."
  [{global :global db-config :database :or {db-config {}} :as config}]
  ;; If the user has none of these settings, then they've either been
  ;; relying on the old HSQLDB default, or they just have no settings.
  ;; Testing not-any? is fine because we catch partial configurations
  ;; elsewhere.
  (when (not-any? db-config [:classname :subprotocol :subname])
    (let [default (hsql-default-connection (:vardir global))
          setting-needed #(when-not (% db-config)
                            (format "\n  %s = %s" (name %) (% default)))
          msg (str "database configuration is now required."
                   " Previously HSQLDB was the default, but support has been deprecated."
                   " If you were relying on that default,"
                   " please add this to the [database] section of your config file:"
                   (setting-needed :classname)
                   (setting-needed :subprotocol)
                   (setting-needed :subname))]
      (throw+ {:type ::cli-error
               :message msg})))
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
  (->> puppetdb
       (s/validate puppetdb-config-in)
       (assoc config :puppetdb)))

(defn convert-config
  "Given a `config` map (created from the user defined config), validate, default and convert it
   to the internal Clojure format that PuppetDB expects"
  [config]
  (-> config
      (configure-section :database write-database-config-in write-database-config-out)
      configure-read-db
      (configure-section :command-processing command-processing-in command-processing-out)
      configure-puppetdb))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Global Config

(defn validate-vardir
  "Checks that `vardir` is specified, exists, and is writeable, throwing
  appropriate exceptions if any condition is unmet."
  [config]
  (let [vardir (io/file (get-in config [:global :vardir]))]
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

(defn catalog-debug-path
  "Given a `config` create the path to the directory directory to store
   catalog debug info."
  [{{vardir :vardir} :global}]
  {:pre [(not (str/blank? vardir))]}
  (fs/file vardir "debug" "catalog-hashes"))

(defn create-catalog-debug-dir
  "Attempt to crate the catalog debug directory at `path`. Failing to create the
   directory only causes a warning as not having this directory shouldn't cause
   PuppetDB to crash on startup."
  [path]
  (try
    (do (fs/mkdirs path) (str path))
    (catch SecurityException e
      (log/warnf e
                (str "catalog-hash-conflig-debugging was enabled, "
                     "but PuppetDB was not able to create a directory at %s")
                path))))

(def ^{:doc "Create the directory for catalog debug info if it does not already
              exist, returning the path if successful (or it already exists)"}
  ensure-catalog-debug-dir
  (comp create-catalog-debug-dir catalog-debug-path))

(defn configure-catalog-debugging
  "When [global] contains catalog-hash-conflict-debugging=true, assoc into the config the directory
   to store the debugging, if not return the config unmodified."
  [config]
  (if-let [debug-dir (and (kitchensink/true-str? (get-in config [:global :catalog-hash-conflict-debugging]))
                          (ensure-catalog-debug-dir config))]
    (do
      (log/warn (str "Global config catalog-hash-conflict-debugging set to true. "
                     "This is intended to troubleshoot catalog duplication issues and "
                     "not for enabling in production long term.  See the PuppetDB docs "
                     "for more information on this setting."))
      (assoc-in config [:global :catalog-hash-debug-dir] debug-dir))
    config))

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
  "Warn a user they are using the old [repl] block, instead of [nrepl]."
  [config-data]
  (when (get-in config-data [:repl])
    (utils/println-err (str "The configuration block [repl] is now retired and will be ignored."
                            " Use [nrepl] instead. Consult the documentation for more details.")))
  (when (get-in config-data [:global :url-prefix])
    (utils/println-err (str "The configuration item `url-prefix` in the [global] section is retired,"
                            " please remove this item from your config."
                            " PuppetDB has a non-configurable context route of `/pdb`."
                            " Consult the documentation for more details."))
    (System/exit 1))
  config-data)

(defn add-web-routing-service-config
  [config-data]
  (let [default-web-router-service {:puppetlabs.puppetdb.metrics/metrics-service "/metrics"
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
    (update config-data :web-router-service merge default-web-router-service
            (when dashboard-redirect?
              {:puppetlabs.puppetdb.dashboard/dashboard-redirect-service "/"}))))

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
      validate-vardir
      convert-config
      configure-catalog-debugging))
