(ns com.puppetlabs.puppetdb.config
  "Centralized place for reading a user-defined config INI file, validating,
   defaulting and converting into a format that can startup a PuppetDB instance.

   The schemas in this file define what is expected to be present in the INI file
   and the format expected by the rest of the application."
  (:import [java.security KeyStore])
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.ssl :as ssl]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.time :as pl-time]
            [clj-time.core :as time]
            [clojure.java.io :as io]
            [fs.core :as fs]
            [clojure.string :as str]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.schema :as pls]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

;; The config is currently broken into the sections that are defined
;; in the INI file. When the schema defaulting code gets changed to
;; support defaulting/converting nested maps, these configs can be put
;; together in a single schema that defines the config for PuppetDB

(def database-config-in
  "Schema for incoming database config (user defined)"
  {(s/optional-key :log-slow-statements) (pls/defaulted-maybe s/Int 10)
   (s/optional-key :conn-max-age) (pls/defaulted-maybe s/Int 60)
   (s/optional-key :conn-keep-alive) (pls/defaulted-maybe s/Int 45)
   (s/optional-key :conn-lifetime) (s/maybe s/Int)
   (s/optional-key :classname) (s/maybe String)
   (s/optional-key :subprotocol) (s/maybe String)
   (s/optional-key :subname) (s/maybe String)
   (s/optional-key :username) String
   (s/optional-key :password) String
   (s/optional-key :syntax_pgs) String
   (s/optional-key :read-only?) (pls/defaulted-maybe String "false")
   (s/optional-key :partition-conn-min) (pls/defaulted-maybe s/Int 1)
   (s/optional-key :partition-conn-max) (pls/defaulted-maybe s/Int 25)
   (s/optional-key :partition-count) (pls/defaulted-maybe s/Int 1)
   (s/optional-key :stats) (pls/defaulted-maybe String "true")
   (s/optional-key :log-statements) (pls/defaulted-maybe String "true")})

(def write-database-config-in
  "Includes the common database config params, also the write-db specific ones"
  (merge database-config-in
         {(s/optional-key :gc-interval) (pls/defaulted-maybe s/Int 60)
          (s/optional-key :report-ttl) (pls/defaulted-maybe String "14d")
          (s/optional-key :node-purge-ttl) (pls/defaulted-maybe String "0s")
          (s/optional-key :node-ttl) (s/maybe String)
          (s/optional-key :node-ttl-days) (s/maybe s/Int)}))

(def database-config-out
  "Schema for parsed/processed database config"
  {:classname String
   :subprotocol String
   :subname String
   :log-slow-statements pls/Days
   :conn-max-age pls/Minutes
   :conn-keep-alive pls/Minutes
   :read-only? pls/SchemaBoolean
   :partition-conn-min s/Int
   :partition-conn-max s/Int
   :partition-count s/Int
   :stats pls/SchemaBoolean
   :log-statements pls/SchemaBoolean
   (s/optional-key :conn-lifetime) (s/maybe pls/Minutes)
   (s/optional-key :username) String
   (s/optional-key :password) String
   (s/optional-key :syntax_pgs) String})

(def write-database-config-out
  "Schema for parsed/processed database config that includes write database params"
  (merge database-config-out
         {:gc-interval pls/Minutes
          :report-ttl pls/Period
          :node-purge-ttl pls/Period
          :node-ttl (s/either pls/Period pls/Days)}))

(defn half-the-cores*
  "Function for computing half the cores of the system, useful
   for testing."
  []
  (-> (kitchensink/num-cpus)
      (/ 2)
      (int)
      (max 1)))

(def half-the-cores
  "Half the number of CPU cores, used for defaulting the number of
   command processors"
  (half-the-cores*))

(def command-processing-in
  "Schema for incoming command processing config (user defined) - currently incomplete"
  {(s/optional-key :dlo-compression-threshold) (pls/defaulted-maybe String "1d")
   (s/optional-key :threads) (pls/defaulted-maybe s/Int half-the-cores)
   (s/optional-key :store-usage) s/Int
   (s/optional-key :temp-usage) s/Int})

(def command-processing-out
  "Schema for parsed/processed command processing config - currently incomplete"
  {:dlo-compression-threshold pls/Period
   :threads s/Int
   (s/optional-key :store-usage) s/Int
   (s/optional-key :temp-usage) s/Int})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database config 

(defn maybe-days
  "Convert the non-nil integer to days"
  [days-int]
  (when days-int
    (time/days days-int)))

(defn hsql-default-connection
  "Returns a map of default, file-backed, HyperSQL connection information"
  [global]
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (format "file:%s;hsqldb.tx=mvcc;sql.syntax_pgs=true" (io/file (:vardir global) "db"))})

(defn defaulted-db-connection
  "Adds each of `hsql-default-connection` keypairs to `database` if classname, subprotocol and subname
   are not found in the `database` config"
  [global database]
  (if (not-any? database [:classname :subprotocol :subname])
    (into database (hsql-default-connection global))
    database))

(defn convert-db-config
  "Converts a `db-config` to `db-schema-out` using defaults from `db-schema-in`. Will also
   add default database connection information if it doesn't already have it"
  [db-schema-in db-schema-out global-config db-config]
  (->> (pls/defaulted-data db-schema-in db-config)
       (defaulted-db-connection global-config)
       (pls/convert-to-schema db-schema-out)))

(defn configure-read-db
  "Validates, defaults and converts the given `config` (i.e. created from a user provided PuppetDB config),
   to a read database config"
  [{:keys [global read-database database] :as config}]
  (let [db-config (pls/strip-unknown-keys database-config-out
                                          (if read-database
                                            (do
                                              (s/validate database-config-in read-database)
                                              (convert-db-config database-config-in database-config-out global read-database))
                                            (assoc database :read-only? true)))]
    (s/validate database-config-out db-config)
    (assoc config :read-database db-config)))

(defn default-node-ttl
  "Assoc into `db-config` a :node-ttl when not already present. Default to node-ttl-days, if that's not there,
   use 0 seconds"
  [db-config]
  (if (:node-ttl db-config)
    db-config
    (assoc db-config :node-ttl (or (maybe-days (:node-ttl-days db-config))
                                   (pl-time/parse-period "0s")))))

(defn convert-write-db-config
  "Converts the `database` config using the write database config schema. Also defaults
   the node-ttl parameter."
  [global database]
  (->> (convert-db-config write-database-config-in write-database-config-out global database)
       default-node-ttl
       (pls/strip-unknown-keys write-database-config-out)))

(defn configure-write-db
  "Convert the gc related parameters of the user defined config to the
   correct internal types."
  [{:keys [global database] :as config :or {database {}}}]
  (s/validate write-database-config-in database)
  (let [db-config (convert-write-db-config global database)]
    (s/validate write-database-config-out db-config)
    (assoc config :database db-config)))

(defn configure-dbs
  "Given a `config` map (created from the user provided config), validate, default and convert the database parameters"
  [config]
  (-> config
      configure-write-db
      configure-read-db))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Command Processing Config

(defn configure-command-params
  "Validates and converts the command-processing portion of the PuppetDB config"
  [{:keys [command-processing] :as config :or {command-processing {}}}]
  (s/validate command-processing-in command-processing)
  (let [converted-config (->> command-processing
                              (pls/defaulted-data command-processing-in)
                              (pls/convert-to-schema command-processing-out))]
    (s/validate command-processing-out converted-config)
    (assoc config :command-processing converted-config)))

(defn convert-config
  "Given a `config` map (created from the user defined config), validate, default and convert it
   to the internal Clojure format that PuppetDB expects"
  [config]
  (-> config
      configure-dbs
      configure-command-params))

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
  [config]
  {:pre [(not (str/blank? (get-in config [:global :vardir])))]}
  (fs/file (get-in config [:global :vardir]) "debug" "catalog-hashes"))

(defn create-catalog-debug-dir
  "Attempt to crate the catalog debug directory at `path`. Failing to create the
   directory only causes a warning as not having this directory shouldn't cause
   PuppetDB to crash on startup."
  [path]
  (try
    (str (fs/mkdirs path))
    (catch SecurityException e
      (log/warn e
                (format (str "catalog-hash-conflig-debugging was enabled, "
                             "but PuppetDB was not able to create a directory at %s")
                        path)))))

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

(defn assoc-when
  "Assoc to `m` only when `k` is not already present in `m`"
  [m k v]
  (if (get m k)
    m
    (assoc m k v)))

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
  [{:keys [global] :as config}]
  (let [product-name (normalize-product-name (get global :product-name "puppetdb"))]
    (update-in config [:global]
               (fn [global-config]
                 (-> global-config
                     (assoc :product-name product-name)
                     (assoc-when :event-query-limit 20000)
                     (assoc-when :update-server "http://updates.puppetlabs.com/check-for-updates"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public


(defn process-config!
  "Accepts a map containing all of the user-provided configuration values
  and configures the various PuppetDB subsystems."
  [config]
  (-> config
      configure-globals
      validate-vardir
      convert-config
      configure-catalog-debugging))
