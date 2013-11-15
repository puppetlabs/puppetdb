(ns com.puppetlabs.puppetdb.config
  (:import [java.security KeyStore])
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.ssl :as ssl]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.time :as pl-time]
            [com.puppetlabs.utils.logging :refer [configure-logging!]]
            [clj-time.core :as time]
            [clojure.java.io :as io]
            [fs.core :as fs]
            [clojure.string :as str]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.schema :as pls]))

(defn configure-commandproc-threads
  "Update the supplied config map with the number of
  command-processing threads to use. If no value exists in the config
  map, default to half the number of CPUs. If only one CPU exists, we
  will use one command-processing thread."
  [config]
  {:pre  [(map? config)]
   :post [(map? %)
          (pos? (get-in % [:command-processing :threads]))]}
  (let [default-nthreads (-> (kitchensink/num-cpus)
                             (/ 2)
                             (int)
                             (max 1))]
    (update-in config [:command-processing :threads] #(or % default-nthreads))))

(defn configure-web-server-ssl-from-pems
  "Configures the web server's SSL settings based on Puppet PEM files, rather than
  via a java keystore (jks) file.  The configuration map returned by this function
  will have overwritten any existing keystore-related settings to use in-memory
  KeyStore objects, which are constructed based on the values of
  `:ssl-key`, `:ssl-cert`, and `:ssl-ca-cert` from
  the input map.  The output map does not include the `:puppet-*` keys, as they
  are not meaningful to the web server implementation."
  [{:keys [ssl-key ssl-cert ssl-ca-cert] :as jetty}]
  {:pre  [ssl-key
          ssl-cert
          ssl-ca-cert]
   :post [(map? %)
          (instance? KeyStore (:keystore %))
          (string? (:key-password %))
          (instance? KeyStore (:truststore %))
          (kitchensink/missing? % :trust-password :ssl-key :ssl-cert :ssl-ca-cert)]}
  (let [old-ssl-config-keys [:keystore :truststore :key-password :trust-password]
        old-ssl-config      (select-keys jetty old-ssl-config-keys)]
    (when (pos? (count old-ssl-config))
      (log/warn (format "Found settings for both keystore-based and Puppet PEM-based SSL; using PEM-based settings, ignoring %s"
                  (keys old-ssl-config)))))
  (let [truststore  (-> (ssl/keystore)
                        (ssl/assoc-cert-file! "PuppetDB CA" ssl-ca-cert))
        keystore-pw (kitchensink/uuid)
        keystore    (-> (ssl/keystore)
                        (ssl/assoc-private-key-file! "PuppetDB Agent Private Key" ssl-key keystore-pw ssl-cert))]
    (-> jetty
        (dissoc :ssl-key :ssl-ca-cert :ssl-cert :trust-password)
        (assoc :keystore keystore)
        (assoc :key-password keystore-pw)
        (assoc :truststore truststore))))

(defn jetty7-minimum-threads
  "Given a thread count, make sure it meets the minimum count for Jetty 7 to
  operate. It will return a warning if it does not, and return the minimum
  instead of the original value.

  This is to work-around a bug/feature in Jetty 7 that blocks the web server
  when max-threads is less than the number of cpus on a system.

  See: http://projects.puppetlabs.com/issues/22168 for more details.

  This bug is solved in Jetty 9, so this check can probably be removed if we
  upgrade."
  ([threads]
  (jetty7-minimum-threads threads (inc (kitchensink/num-cpus))))

  ([threads min-threads]
  {:pre [(pos? threads)
         (pos? min-threads)]
   :post [(pos? %)]}
  (if (< threads min-threads)
    (do
      (log/warn (format "max-threads = %s is less than the minium allowed on this system for Jetty 7 to operate. This will be automatically increased to the safe minimum: %s"
                  threads min-threads))
      min-threads)
    threads)))

(defn configure-web-server
  "Update the supplied config map with information about the HTTP webserver to
  start. This will specify client auth, and add a default host/port
  http://puppetdb:8080 if none are supplied (and SSL is not specified)."
  [{:keys [jetty] :as config}]
  {:pre  [(map? config)]
   :post [(map? %)
          (kitchensink/missing? (:jetty %) :ssl-key :ssl-cert :ssl-ca-cert)]}
  (let [initial-config  {:max-threads 50}
        merged-jetty    (merge initial-config jetty)
        pem-required-keys [:ssl-key :ssl-cert :ssl-ca-cert]
        pem-config        (select-keys jetty pem-required-keys)]
    (assoc config :jetty
      (-> (condp = (count pem-config)
            3 (configure-web-server-ssl-from-pems jetty)
            0 jetty
            (throw (IllegalArgumentException.
                     (format "Found SSL config options: %s; If configuring SSL from Puppet PEM files, you must provide all of the following options: %s"
                        (keys pem-config) pem-required-keys))))
          (assoc :client-auth :need)
          (assoc :max-threads (jetty7-minimum-threads (:max-threads merged-jetty)))))))

(def database-config-in
  "Schema for incoming database config (user defined)"
  {(s/optional-key :gc-interval) (pls/defaulted-maybe s/Int 60)
   (s/optional-key :report-ttl) (pls/defaulted-maybe s/String "14d")
   (s/optional-key :node-purge-ttl) (pls/defaulted-maybe s/String "0s")
   (s/optional-key :node-ttl) (s/maybe s/String)
   (s/optional-key :node-ttl-days) (s/maybe s/Int)})

(def database-config-out
  "Schema for parsed/processed database config"
  {:gc-interval pls/Minutes
   :report-ttl pls/Period
   :node-purge-ttl pls/Period
   :node-ttl (s/either pls/Period pls/Days)})

(def command-processing-in
  "Schema for incoming command processing config (user defined) - currently incomplete"
  {(s/optional-key :dlo-compression-threshold) (pls/defaulted-maybe s/String "1d")})

(def command-processing-out
  "Schema for parsed/processed command processing config - currently incomplete"
  {:dlo-compression-threshold pls/Period})

(defn maybe-days
  "Convert the non-nil integer ot days"
  [days-int]
  (when days-int
    (time/days days-int)))

(s/defn configure-gc-params* :- database-config-out
  "Convert the gc related parameters of the user defined config to the
   correct internal types."
  [config :- database-config-in]
  (let [gc-conf (pls/convert-to-schema database-config-out config)]
    (pls/strip-unknown-keys database-config-out
                            (if (:node-ttl gc-conf)
                              gc-conf
                              (assoc gc-conf :node-ttl (or (maybe-days (:node-ttl-days gc-conf))
                                                           (pl-time/parse-period "0s")))))))

(s/defn command-processing-params :- command-processing-in
  "Convert the gc related command processing params to the correct internal format"
  [config :- command-processing-out]
  (pls/convert-to-schema command-processing-out config))

(defn configure-gc-params
  "Take a user defined config and default missing values and convert
   the results to the proper internal format."
  [config]
  (-> config
      (update-in [:database]
                 (fn [db-config]
                   (merge db-config (configure-gc-params* (pls/defaulted-data database-config-in db-config)))))
      (update-in [:command-processing]
                 (fn [cmd-processing]
                   (merge cmd-processing (command-processing-params (pls/defaulted-data command-processing-in cmd-processing)))))))

(defn default-db-config [global]
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (format "file:%s;hsqldb.tx=mvcc;sql.syntax_pgs=true" (io/file (:vardir global) "db"))})

(defn configure-database
  "Update the supplied config map with information about the database. Adds a
  default hsqldb. If a single part of the database information is specified
  (such as classname but not subprotocol), no defaults will be filled in."
  [{:keys [database read-database global] :as config}]
  {:pre  [(map? config)]
   :post [(map? config)]}
  (let [write-db (or database (default-db-config global))
        read-db (or read-database write-db)]
    (assoc config
      :database write-db
      :read-database (assoc read-db :read-only? true))))

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

(defn assoc-when [m k v & kvs]
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

(defn configure-globals [{:keys [global] :as config}]
  (let [product-name (normalize-product-name (get global :product-name "puppetdb"))]
    (update-in config [:global]
               (fn [global-config]
                 (-> global-config
                     (assoc :product-name product-name)
                     (assoc-when :event-query-limit 20000)
                     (assoc-when :update-server "http://updates.puppetlabs.com/check-for-updates"))))))

(defn parse-config
  "Parses the given config file/directory and configures its various
  subcomponents.

  Also accepts an optional map argument 'initial-config'; if
  provided, any initial values in this map will be included
  in the resulting config map."
  ([path]
    (parse-config path {}))
  ([path initial-config]
    {:pre [(string? path)
           (map? initial-config)]}
    (let [file (io/file path)]
      (when-not (.canRead file)
        (throw (IllegalArgumentException.
                (format "Configuration path '%s' must exist and must be readable." path)))))

    (->> (kitchensink/inis-to-map path)
         (merge initial-config)
         configure-globals
         pl-utils/configure-logging!
         validate-vardir
         configure-commandproc-threads
         configure-web-server
         configure-database
         configure-gc-params
         configure-catalog-debugging)))
