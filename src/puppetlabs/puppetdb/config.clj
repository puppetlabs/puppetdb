(ns puppetlabs.puppetdb.config
  "Centralized place for reading a user-defined config INI file, validating,
   defaulting and converting into a format that can startup a PuppetDB instance.

   The schemas in this file define what is expected to be present in the INI file
   and the format expected by the rest of the application."
  (:require [puppetlabs.i18n.core :refer [trs]]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [schema.core :as s]
            [puppetlabs.puppetdb.cli.util :refer [err-exit-status]]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils
             :refer [call-unless-shutting-down throw-if-shutdown-pending]]
            [puppetlabs.puppetdb.time :as t]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]])
  (:import
   (clojure.lang ExceptionInfo)
   (java.security KeyStore)
   (java.util.regex PatternSyntaxException Pattern)
   (org.joda.time Minutes Days Period)))

(defn throw-cli-error [msg]
  (throw (ex-info msg {:type ::cli-error :message msg})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

;; The config is currently broken into the sections that are defined
;; in the INI file. When the schema defaulting code gets changed to
;; support defaulting/converting nested maps, these configs can be put
;; together in a single schema that defines the config for PuppetDB

(defn warn-unknown-keys
  [schema data]
  (doseq [k (pls/unknown-keys schema data)]
    (log/warn
     (trs "The configuration item `{0}` does not exist and should be removed from the config." k))))

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

(def per-database-config-in
  "Schema for incoming database config (user defined)"
  (all-optional
    {:conn-max-age (pls/defaulted-maybe s/Int 60)
     :conn-lifetime (s/maybe s/Int)
     :maximum-pool-size (pls/defaulted-maybe s/Int 25)
     :subname (s/maybe String)
     :connection-migrator-username String
     :connection-username String
     :user String
     :username String
     :password String
     :migrator-username String
     :migrator-password String
     :syntax_pgs String
     :read-only? (pls/defaulted-maybe String "false")
     :partition-conn-min (pls/defaulted-maybe s/Int 1)
     :partition-conn-max (pls/defaulted-maybe s/Int 25)
     :partition-count (pls/defaulted-maybe s/Int 1)
     :stats (pls/defaulted-maybe String "true")
     :log-statements (pls/defaulted-maybe String "true")
     :connection-timeout (pls/defaulted-maybe s/Int 3000)

     ;; preserve backwards compatability with facts-blacklist
     :facts-blacklist pls/Blocklist
     :facts-blacklist-type (pls/defaulted-maybe (s/enum "literal" "regex") "literal")
     :facts-blocklist pls/Blocklist
     ;; blocklist-type will get defaulted to value of blacklist-type
     ;; if unset in pls/convert-blacklist-settings-to-blocklist
     :facts-blocklist-type (s/enum "literal" "regex")

     :schema-check-interval (pls/defaulted-maybe s/Int (* 30 1000))
     ;; This is applicable to the read-database too because it's used
     ;; to limit the scope of some queries.
     :node-purge-ttl (pls/defaulted-maybe String "14d")
     ;; FIXME?
     ;; completely retired (ignored)
     :classname (pls/defaulted-maybe String "org.postgresql.Driver")
     :conn-keep-alive s/Int
     :log-slow-statements s/Int
     :statements-cache-size s/Int
     :subprotocol (pls/defaulted-maybe String "postgresql")}))

(def report-ttl-default "14d")

(def per-write-database-config-in
  "Includes the common database config params, also the write-db specific ones"
  (merge per-database-config-in
         (all-optional
           {:gc-interval (pls/defaulted-maybe (s/cond-pre String s/Int) "60")
            :report-ttl (pls/defaulted-maybe String report-ttl-default)
            :node-purge-gc-batch-limit (pls/defaulted-maybe s/Int 25)
            :node-ttl (pls/defaulted-maybe String "7d")
            :resource-events-ttl String
            :migrate (pls/defaulted-maybe String "true")})))

(def per-database-config-out
  "Schema for parsed/processed database config"
  {:subname String
   :conn-max-age Minutes
   :read-only? Boolean
   :partition-conn-min s/Int
   :partition-conn-max s/Int
   :partition-count s/Int
   :stats Boolean
   :log-statements Boolean
   :connection-timeout s/Int
   :maximum-pool-size s/Int
   (s/optional-key :conn-lifetime) (s/maybe Minutes)
   (s/optional-key :connection-migrator-username) String
   (s/optional-key :connection-username) String
   (s/optional-key :user) String
   (s/optional-key :username) String
   (s/optional-key :password) String
   (s/optional-key :migrator-username) String
   (s/optional-key :migrator-password) String
   (s/optional-key :syntax_pgs) String
   (s/optional-key :facts-blocklist) clojure.lang.PersistentVector
   :facts-blocklist-type String

   :schema-check-interval s/Int
   :node-purge-ttl Period
   ;; completely retired (ignored)
   :classname String
   (s/optional-key :conn-keep-alive) Minutes
   (s/optional-key :log-slow-statements) Days
   (s/optional-key :statements-cache-size) s/Int
   :subprotocol String})

(def per-write-database-config-out
  "Schema for parsed/processed database config that includes write database params"
  (merge per-database-config-out
         {:gc-interval (s/cond-pre String s/Int)
          :report-ttl Period
          :node-purge-gc-batch-limit (s/constrained s/Int (complement neg?))
          :node-ttl Period
          (s/optional-key :resource-events-ttl) Period
          :migrate Boolean}))

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
    {:threads (pls/defaulted-maybe s/Int half-the-cores)
     :max-command-size (pls/defaulted-maybe s/Int (default-max-command-size))
     :reject-large-commands (pls/defaulted-maybe String "false")
     :concurrent-writes (pls/defaulted-maybe s/Int (min half-the-cores 4))

     ;; Deprecated
     :max-frame-size (pls/defaulted-maybe s/Int 209715200)
     :store-usage s/Int
     :temp-usage s/Int
     :memory-usage s/Int}))

(def command-processing-out
  "Schema for parsed/processed command processing config - currently incomplete"
  {:threads s/Int
   :max-command-size s/Int
   :reject-large-commands Boolean
   :concurrent-writes s/Int

   ;; Deprecated
   :max-frame-size s/Int
   (s/optional-key :memory-usage) s/Int
   (s/optional-key :store-usage) s/Int
   (s/optional-key :temp-usage) s/Int})

(def puppetdb-config-in
  "Schema for validating the incoming [puppetdb] block"
  (all-optional
   {:certificate-whitelist s/Str
    :certificate-allowlist s/Str
    :historical-catalogs-limit (pls/defaulted-maybe s/Int 0)
    :disable-update-checking (pls/defaulted-maybe String "false")
    :add-agent-report-filter (pls/defaulted-maybe String "true")
    :log-queries (pls/defaulted-maybe String "false")}))

(def puppetdb-config-out
  "Schema for validating the parsed/processed [puppetdb] block"
  {(s/optional-key :certificate-allowlist) s/Str
   :historical-catalogs-limit s/Int
   :disable-update-checking Boolean
   :add-agent-report-filter Boolean
   :log-queries Boolean})

(def developer-config-in
  (all-optional
   {:pretty-print (pls/defaulted-maybe String "false")
    :max-enqueued (pls/defaulted-maybe s/Int 1000000)}))

(def developer-config-out
  {:pretty-print Boolean
   :max-enqueued s/Int})

(pls/defn-validated write-databases
  "Returns a map of database names to their configs.  Each
  :database-NAME section in the config will produce a map entry with
  NAME as the key.  If the config only contains a single :database
  section, its key will be \"default\" and its config map will
  have ::unnamed set to true."
  [config]
  (let [prefix "database-"
        result (reduce-kv (fn [result k cfg]
                            (let [n (name k)]
                              (if (str/starts-with? n prefix)
                                (assoc result
                                       (subs n (count prefix))
                                       cfg)
                                result)))
                          {}
                          config)]
    (if (seq result)
      result
      {"default" (assoc (:database config)
                        ::unnamed true)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database config

(defn check-fact-regex [fact]
  (try
    (re-pattern fact)
    (catch PatternSyntaxException e
      (.getMessage e))))

(defn convert-blocklist-config
  "Validate and convert facts blocklist section of the config to runtime format.
  Throws a {:type ::cli-error :message m} exception describing errors when compiling
  facts-blocklist regex patterns if :facts-blocklist-type is set to \"regex\"."
  [config]
  (if (and (= "regex" (:facts-blocklist-type config)) (:facts-blocklist config))
    (let [patts (->> config
                     :facts-blocklist
                     pls/blocklist->vector
                     (mapv check-fact-regex))]
      (when-let [errors (seq (filter string? patts))]
        (throw-cli-error
         (apply str (trs "Unable to parse facts-blocklist patterns:\n")
                (interpose "\n" errors))))
      (assoc config :facts-blocklist patts))
    config))

(defn validate-and-default-incoming-config
  [config schema-in]
  (->> (or config {})
       (warn-and-validate schema-in)
       (pls/defaulted-data schema-in)))

(defn coerce-and-validate-final-config
  [config schema-out]
  (->> config
       (pls/convert-to-schema schema-out)
       (s/validate schema-out)))

(defn configure-section
  "Validates the indicated top-level section of an incoming config and
  establishes defaults as specified by schema-in, and then coerces the
  values and validates the final result as specified by schema-out."
  [config section schema-in schema-out]
  (assoc config section
         (-> (section config)
             (validate-and-default-incoming-config schema-in)
             (coerce-and-validate-final-config schema-out))))

(defn prefer-db-user-on-username-mismatch
  [{:keys [user username] :as config} db-section-name]
  ;; match puppetdb.jdbc/make-connection-pool
  (when (and user username (not= user username))
    (log/warn
     (trs "Configured {0} user {1} and username {2} don't match"
          (pr-str db-section-name) (pr-str user) (pr-str username)))
    (log/warn
     (trs "Preferring configured user {0}" (pr-str user))))
  (let [config (update config :user #(or % (:username config)))]
    (assoc config :username (:user config))))

(defn require-db-subname
  "Ensures the database config includes a subname.  Throws a
  {:type ::cli-error :message m} on error."
  [{:keys [subname] :as config} section-key]
  (when (or (not subname) (str/blank? subname))
    (throw-cli-error
     (trs "No subname set in the {0} config." (pr-str (name section-key)))))
  config)

(defn require-enclosing-report-ttl
  "Ensures the report-ttl is at least as long as the
  resource-events-ttl.  Throws a {:type ::cli-error :message m} on error."
  [{:keys [resource-events-ttl report-ttl] :as config} section-key]
  (when (and resource-events-ttl
             (t/period-longer? (t/parse-period resource-events-ttl)
                               (t/parse-period (or report-ttl report-ttl-default))))
    (throw-cli-error
     (trs "The {0} resource-events-ttl must not be longer than report-ttl"
          (pr-str (name section-key)))))
  config)

(defn warn-if-mismatched-node-purge-ttls
  "Warn if the read-database has a node-purge-ttl that doesn't match the
  value for a database with the same subname."
  [config]
  (let [dbs (into (select-keys config [:database :read-database])
                  (filter (fn [[sec cfg]]
                            (str/starts-with? (name sec) "database-"))
                          config))
        cfgs-for-subname (group-by (fn [[sec cfg]] (:subname cfg)) dbs)]
    (doseq [[subname sec-and-cfgs] cfgs-for-subname
            :when (> (count (set (map (fn [[sec cfg]] (:node-purge-ttl cfg))
                                      sec-and-cfgs)))
                     1)]
      ;; Q: Should this be a cli-error instead?
      (log/warn
       (trs "database configs have same subname, differing node-purge-ttls: {0}"
            (str/join " " (map first sec-and-cfgs))))))
  config)

(defn forbid-duplicate-write-db-subnames
  [config]
  (let [subnames (for [[k db-config] config
                       :when (str/starts-with? (name k) "database-")]
                   (:subname db-config))]
    (when (not= (count subnames) (count (distinct subnames)))
      (throw-cli-error
       (trs "Cannot have duplicate write database subnames"))))
  config)

(defn default-events-ttl [config]
  (update config :resource-events-ttl #(or % (:report-ttl config))))

(defn ensure-long-or-double [x]
  (if (number? x)
    x
    (or (try
          (Long/parseLong x)
          (catch NumberFormatException ex
            false))
        (try
          (Double/parseDouble x)
          (catch NumberFormatException ex
            false))
        (throw-cli-error
         (trs "gc-interval must be a number: {0}" x)))))

(defn gc-interval->ms [config]
  (update config
          :gc-interval
          (fn [minutes]
            (let [ms (* 60 1000 (ensure-long-or-double minutes))]
              (t/millis (cond
                          (> ms 1) ms
                          (> ms 0) 1
                          (< ms 0) (throw-cli-error
                                    (trs "gc-interval cannot be negative: {0}"
                                         minutes))
                          :else 0))))))

(defn using-ssl? [config]
  (and
    (str/includes? (:subname config) "ssl=true")
    (nil? (:password config))))

(defn ensure-migrator-info [config]
  ;; This expects to run after prefer-db-user-on-username-mismatch, so
  ;; the :user should always be the right answer.
  (assert (:user config))
  (-> config
      (update :migrator-username #(or % (:user config)))
      (cond-> (using-ssl? config) (update :migrator-password #(or % ""))
              :always (update :migrator-password #(or % (:password config))))))

(defn ensure-connection-users-info [config]

  ;; This expects to run after ensure-migrator-info and prefer-db-user-on-username-mismatch,
  ;; so the :user and :migrator-user should already be set
  (assert (:user config))
  (assert (:migrator-username config))
  (-> config
      (update :connection-username #(or % (:user config)))
      (update :connection-migrator-username #(or % (:migrator-username config)))))

(defn fix-up-db-settings
  [section-key settings]
  ;; FIXME: double-check this reordering wrt each function,
  ;; i.e. make sure they're OK with going *after* the defaulting.
  (-> settings
      (validate-and-default-incoming-config per-write-database-config-in)
      (require-db-subname section-key)
      (require-enclosing-report-ttl section-key)
      pls/convert-blacklist-settings-to-blocklist
      convert-blocklist-config
      (coerce-and-validate-final-config per-write-database-config-out)
      gc-interval->ms
      default-events-ttl
      (prefer-db-user-on-username-mismatch (name section-key))
      ensure-migrator-info
      ensure-connection-users-info))

(defn configure-read-db
  "Ensures that the config contains a suitable [read-database].  If the
  section already exists, validates and converts it to the internal
  format.  Otherwise, creates it from values in the [database]
  section, which must have already been fully configured."
  [{:keys [database read-database] :as config}]
  (assoc config
         :read-database
         (if read-database
           (-> read-database
               (validate-and-default-incoming-config per-database-config-in)
               pls/convert-blacklist-settings-to-blocklist
               (require-db-subname :read-database)
               (coerce-and-validate-final-config per-database-config-out)
               (prefer-db-user-on-username-mismatch "read-database"))
           ;; Adapt the :database config for use as the read-database
           (->> (assoc database :read-only? true)
                (pls/strip-unknown-keys per-database-config-out)
                (s/validate per-database-config-out)))))

(defn configure-dbs
  [config]
  (let [db-rx #"^database-.*"
        db-section? (fn [k] (re-find db-rx (name k)))
        defaults (:database config)]
    (-> config
        ;; Each database-* section becomes complete configuration.
        ;; Merge defaults first, so that operations like
        ;; prefer-db-user-on-username-mismatch will work correctly.
        (utils/update-matching-keys db-section? (fn [k v] (merge defaults v)))
        (update :database #(fix-up-db-settings :database %1))
        (utils/update-matching-keys db-section? fix-up-db-settings)
        configure-read-db
        warn-if-mismatched-node-purge-ttls
        forbid-duplicate-write-db-subnames)))

(defn convert-certificate-whitelist-to-allowlist
  [config]
  (let [{:keys [certificate-whitelist
                certificate-allowlist]} (:puppetdb config)
        allowlist-value (or certificate-allowlist certificate-whitelist)]
    (when (and certificate-allowlist certificate-whitelist)
      (let [msg (trs "Confusing configuration settings found! Both the deprecated certificate-whitelist and replacement certificate-allowlist are set. These settings are mutually exclusive, please prefer certificate-allowlist.")]
        (throw (ex-info msg {:type ::cli-error :message msg}))))
    (when certificate-whitelist
      (log/warn (trs "The certificate-whitelist setting has been deprecated and will be removed in a future release. Please use certificate-allowlist instead.")))
    (cond-> config
      certificate-whitelist (update-in [:puppetdb] dissoc :certificate-whitelist)
      allowlist-value (assoc-in [:puppetdb :certificate-allowlist] allowlist-value))))

(defn configure-puppetdb
  "Validates the [puppetdb] section of the config"
  [{:keys [puppetdb] :as config :or {puppetdb {}}}]
  (-> config
      convert-certificate-whitelist-to-allowlist
      (configure-section :puppetdb puppetdb-config-in puppetdb-config-out)))

(defn configure-developer
  [{:keys [developer] :as config :or {developer {}}}]
  (configure-section config :developer developer-config-in developer-config-out))

(def retired-cmd-proc-keys
  [:store-usage :max-frame-size :temp-usage :memory-usage])

(defn warn-command-processing-retirements
  [config]
  (doseq [cmd-proc-key retired-cmd-proc-keys]
    (when (get-in config [:command-processing cmd-proc-key])
      (utils/println-err
       (str (trs "The configuration item `{0}` in the [command-processing] section is retired, please remove this item from your config."
                 (name cmd-proc-key))
            " "
            (trs "Consult the documentation for more details."))))))

(defn configure-command-processing
  [config]
  (warn-command-processing-retirements config)
  (configure-section config :command-processing command-processing-in command-processing-out))

(defn convert-config
  "Given a `config` map (created from the user defined config), validate, default and convert it
   to the internal Clojure format that PuppetDB expects"
  [config]
  (-> config
      configure-dbs
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
             (format "%s %s"
                     (trs "Required setting ''vardir'' is not specified.")
                     (trs "Please set it to a writable directory."))))

     (not (.isAbsolute vardir))
     (throw (IllegalArgumentException.
             (trs "Vardir {0} must be an absolute path." vardir)))

     (not (.exists vardir))
     (throw (java.io.FileNotFoundException.
             (format "%s %s"
                     (trs "Vardir {0} does not exist." vardir)
                     (trs "Please create it and ensure it is writable."))))

     (not (.isDirectory vardir))
     (throw (java.io.FileNotFoundException.
             (trs "Vardir {0} is not a directory." vardir)))

     (not (.canWrite vardir))
     (throw (java.io.FileNotFoundException.
             (trs "Vardir {0} is not writable." vardir)))

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
              (trs "product-name {0} is illegal; either puppetdb or pe-puppetdb are allowed" product-name))))
    lower-product-name))

(defn configure-globals
  "Configures the global properties from the user defined config"
  [config]
  (update config :global
          #(-> %
               (utils/assoc-when :product-name "puppetdb")
               (update :product-name normalize-product-name)
               (utils/assoc-when :update-server "https://updates.puppetlabs.com/check-for-updates"))))

(defn warn-retirements
  "Warns about configuration retirements.  Abruptly exits the entire
  process if a [global] url-prefix is found."
  [config-data]

  (doseq [[section opt] [[:command-processing :max-frame-size]
                         [:command-processing :memory-usage]
                         [:command-processing :store-usage]
                         [:command-processing :temp-usage]
                         [:database :classname]
                         [:database :conn-keep-alive]
                         [:database :log-slow-statements]
                         [:database :statements-cache-size]
                         [:database :subprotocol]
                         [:read-database :classname]
                         [:read-database :conn-keep-alive]
                         [:read-database :log-slow-statements]
                         [:read-database :statements-cache-size]
                         [:read-database :subprotocol]
                         [:global :catalog-hash-conflict-debugging]]]
    (when (contains? (config-data section) opt)
      (utils/println-err
       (trs "The [{0}] {1} config option has been retired and will be ignored."
            (name section) (name opt)))))

  (when (get-in config-data [:repl])
    (utils/println-err (format "%s %s %s"
                               (trs "The configuration block [repl] is now retired and will be ignored.")
                               (trs "Use [nrepl] instead.")
                               (trs "Consult the documentation for more details."))))

  (when (get-in config-data [:global :url-prefix])
    (utils/println-err (format "%s %s %s"
                               (trs "The configuration item `url-prefix` in the [global] section is retired, please remove this item from your config.")
                               (trs "PuppetDB has a non-configurable context route of `/pdb`.")
                               (trs "Consult the documentation for more details.")))
    (utils/flush-and-exit err-exit-status)) ; cf. PDB-2053
  config-data)

(def default-web-router-config
  {:puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-webservice
   {:route "/metrics"
    :server "default"}
   :puppetlabs.trapperkeeper.services.status.status-service/status-service
   {:route "/status"
    :server "default"}
   :puppetlabs.puppetdb.pdb-routing/pdb-routing-service
   {:route "/pdb"
    :server "default"}
   :puppetlabs.puppetdb.dashboard/dashboard-redirect-service
   {:route "/"
    :server "default"}})

(defn filter-out-non-tk-config [config-data]
  (select-keys config-data
               [:debug :bootstrap-config :config :plugins :help]))

(defn add-web-routing-service-config
  [config-data]
  (let [bootstrap-cfg (->> (tk-bootstrap/find-bootstrap-configs (filter-out-non-tk-config config-data))
                           (mapcat tk-bootstrap/read-config)
                           set)
        ;; If a user didn't specify one of the services in their bootstrap.cfg
        ;; we remove the web-router-config for that service
        filtered-web-router-config (into {} (for [[svc route] default-web-router-config
                                                  :when (contains? bootstrap-cfg (utils/kwd->str svc))]
                                              [svc route]))]
    (doseq [[svc route] filtered-web-router-config]
      (when (get-in config-data [:web-router-service svc])
        (utils/println-err
         (trs "Configuring the route for `{0}` is not allowed. The default route is `{1}` and server is `{2}`."
              svc (:route route) (:server route)))))
    ;; We override the users settings as to make the above routes *not*
    ;; configurable
    (-> config-data
        (assoc-in [:metrics :reporters :jmx :enabled] true)
        (update :web-router-service merge filtered-web-router-config))))

(def adjust-and-validate-tk-config
  (comp add-web-routing-service-config
        warn-retirements))

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
      convert-config))

(defn foss? [config]
  (= "puppetdb" (get-in config [:global :product-name])))

(defn pe? [config]
  (= "pe-puppetdb" (get-in config [:global :product-name])))

(defn update-server [config]
  (get-in config [:global :update-server]))

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

(defn stockpile-dir [config]
  (str (io/file (get-in config [:global :vardir]) "stockpile")))

(defn init-config-service [context config transform-config request-shutdown]
  (try
    (assoc context :config (transform-config config))
    ;; For now, redirect ::cli-errors to shutdown requests here to
    ;; make it easy to migrate our validators out of the hooke.
    (catch ExceptionInfo ex
      (let [{:keys [type message] :as data} (ex-data ex)
            stop (fn [msg]
                   (log/error msg)
                   (request-shutdown {::tk/exit {:status 2 :messages [[msg *err*]]}})
                   context)]
        (case type
          ::cli-error (stop message)
          ;; Unrecognized -- pass it on.
          (throw ex))))))

(defprotocol DefaultedConfig
  (get-config [this]))

(defn create-defaulted-config-service [transform-config]
  (tk/service
   DefaultedConfig
   [[:ConfigService get-config]
    [:ShutdownService get-shutdown-reason request-shutdown]]

   (init
    [this context]
    ;; This wrapper is just for consistency, for now the config
    ;; service is the root of the dependency tree...
    (call-unless-shutting-down
     "PuppetDB config service init" (get-shutdown-reason) context
     #(init-config-service context (get-config) transform-config request-shutdown)))

   (get-config
    [this]
    (throw-if-shutdown-pending (get-shutdown-reason))
    (:config (service-context this)))))

(def config-service
  (create-defaulted-config-service process-config!))
