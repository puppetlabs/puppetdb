(ns puppetlabs.puppetdb.cli.services
  "Main entrypoint

   PuppetDB consists of several, cooperating components:

   * Command processing

     PuppetDB uses a CQRS pattern for making changes to its domain
     objects (facts, catalogs, etc). Instead of simply submitting data
     to PuppetDB and having it figure out the intent, the intent
     needs to explicitly be codified as part of the operation. This is
     known as a \"command\" (e.g. \"replace the current facts for node
     X\").

     Commands are processed asynchronously, however we try to do our
     best to ensure that once a command has been accepted, it will
     eventually be executed. Ordering is also preserved. To do this,
     all incoming commands are placed in a message queue which the
     command processing subsystem reads from in FIFO order.

     Refer to `puppetlabs.puppetdb.command` for more details.

   * Message queue

     We use stockpile to durably store commands. The \"in memory\"
     representation of that queue is a core.async channel.

   * REST interface

     All interaction with PuppetDB is conducted via its REST API. We
     embed an instance of Jetty to handle web server duties. Commands
     that come in via REST are relayed to the message queue. Read-only
     requests are serviced synchronously.

   * Database sweeper

     As catalogs are modified, unused records may accumulate and stale
     data may linger in the database. We periodically sweep the
     database, compacting it and performing regular cleanup so we can
     maintain acceptable performance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [metrics.counters :as counters :refer [counter inc!]]
            [metrics.gauges :refer [gauge-fn]]
            [metrics.timers :refer [time! timer]]
            [metrics.reporters.jmx :as jmx-reporter]
            [overtone.at-at :refer [mk-pool every interspaced stop-and-reset-pool!]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.meta.version :as version]
            [puppetlabs.puppetdb.metrics.core :as metrics
             :refer [metrics-registries]]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.query.population :as pop]
            [puppetlabs.puppetdb.scf.migrate
             :refer [desired-schema-version
                     initialize-schema
                     pending-migrations
                     require-valid-schema]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.time :refer [ago to-seconds to-millis parse-period
                                              format-period period?]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.core :refer [defservice] :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [robert.hooke :as rh]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.i18n.core :refer [trs tru]])
  (:import
   (clojure.lang ExceptionInfo)
   [javax.jms ExceptionListener]
   [java.util.concurrent.locks ReentrantLock]
   [org.joda.time Period]))

(def database-metrics-registry (get-in metrics/metrics-registries [:database :registry]))

(def clean-options
  #{"expire_nodes" "purge_nodes" "purge_reports" "gc_packages" "other"})

(def purge-nodes-opts-schema {:batch_limit s/Int})

(def clean-request-schema
  ;; i.e. a possibly empty collection of elements, each of which must
  ;; either be a string from clean-options or a purge_nodes command
  ;; with an options map.
  [(s/if string?
     (apply s/enum clean-options)
     [(s/one (s/eq "purge_nodes") "command")
      (s/one purge-nodes-opts-schema "options")])])

(defn reduce-clean-request [request]
  "Converts the incoming vector of requests to a map of requests to
  their options, where the last one of each kind wins.
  e.g. [\"purge_nodes\" \"purge_reports\" {\"purge_nodes\" {...}}]
  becomes #{{\"purge_reports\" true} {\"purge_nodes\" {...}}}."
  (into {} (map (fn [x]
                  (if (string? x)
                    [x true]
                    x))
            request)))

(defn reduced-clean-request->status [request]
  (->> request keys sort
       (map {"expire_nodes" "expiring-nodes"
             "purge_nodes" "purging-nodes"
             "purge_reports" "purging-reports"
             "gc_packages" "package-gc"
             "other" "other"})
       (str/join " ")))

(defn auto-expire-nodes!
  "Expire nodes which haven't had any activity (catalog/fact submission)
  for more than `node-ttl`."
  [node-ttl db]
  {:pre [(map? db)
         (period? node-ttl)]}
  (try
    (kitchensink/demarcate
     (format "sweep of stale nodes (threshold: %s)"
             (format-period node-ttl))
     (jdbc/with-transacted-connection db
       (doseq [node (scf-store/expire-stale-nodes node-ttl)]
         (log/info (trs "Auto-expired node {0}" node)))))
    (catch Exception e
      (log/error e (trs "Error while deactivating stale nodes")))))

(defn-validated purge-nodes!
  "Deletes nodes which have been *deactivated or expired* longer than
  node-purge-ttl.  Deletes at most batch_limit nodes if opts is a map,
  all relevant nodes otherwise."
  [node-purge-ttl :- (s/pred period?)
   opts :- (s/if map? purge-nodes-opts-schema (s/eq true))
   db :- (s/pred map?)]
  (try
    (kitchensink/demarcate
     (format "purge deactivated and expired nodes (threshold: %s)"
             (format-period node-purge-ttl))
     (let [horizon (ago node-purge-ttl)]
       (jdbc/with-transacted-connection db
         (if-let [limit (and (map? opts) (:batch_limit opts))]
           (scf-store/purge-deactivated-and-expired-nodes! horizon limit)
           (scf-store/purge-deactivated-and-expired-nodes! horizon)))))
    (catch Exception e
      (log/error e (trs "Error while purging deactivated and expired nodes")))))

(defn sweep-reports!
  "Delete reports which are older than than `report-ttl`."
  [report-ttl db]
  {:pre [(map? db)
         (period? report-ttl)]}
  (try
    (kitchensink/demarcate
     (format "sweep of stale reports (threshold: %s)"
             (format-period report-ttl))
     (jdbc/with-transacted-connection db
       (scf-store/delete-reports-older-than! (ago report-ttl))))
    (catch Exception e
      (log/error e (trs "Error while sweeping reports")))))

(defn gc-packages! [db]
  {:pre [(map? db)]}
  (try
    (kitchensink/demarcate (trs "gc packages")
      (jdbc/with-transacted-connection db
        (scf-store/delete-unassociated-packages!)))
    (catch Exception e
      (log/error e (trs "Error while running package gc")))))

(defn gc-resource-events!
  []
  (trs "Error while sweeping resource events"))

(defn garbage-collect!
  "Perform garbage collection on `db`, which means deleting any orphaned data.
  This basically just wraps the corresponding scf.storage function with some
  logging and other ceremony. Exceptions are logged but otherwise ignored."
  [db]
  {:pre [(map? db)]}
  (try
    (kitchensink/demarcate
      (trs "database garbage collection")
      (scf-store/garbage-collect! db))
    (catch Exception e
      (log/error e (trs "Error during garbage collection")))))

(def admin-metrics-registry
  (get-in metrics/metrics-registries [:admin :registry]))

(def clean-status (atom ""))

(def admin-metrics
  {;;"expiring-nodes" | "expiring-nodes purging-reports" | ...
   :cleaning (gauge-fn admin-metrics-registry ["cleaning"]
                       #(deref clean-status))

   :node-expirations (counter admin-metrics-registry "node-expirations")
   :node-purges (counter admin-metrics-registry "node-purges")
   :report-purges (counter admin-metrics-registry "report-purges")
   :package-gcs (counter admin-metrics-registry "package-gcs")
   :other-cleans (counter admin-metrics-registry "other-cleans")

   :node-expiration-time (timer admin-metrics-registry ["node-expiration-time"])
   :node-purge-time (timer admin-metrics-registry ["node-purge-time"])
   :report-purge-time (timer admin-metrics-registry ["report-purge-time"])
   :package-gc-time (timer admin-metrics-registry "package-gc-time")
   :other-clean-time (timer admin-metrics-registry ["other-clean-time"])})

(defn- clear-clean-status!
  "Clears the clean status (as a separate function to support tests)."
  []
  (reset! clean-status ""))

(defn-validated clean-up
  "Cleans up the resources specified by request, or everything if
  request is empty?."
  [db
   lock :- ReentrantLock
   {:keys [node-ttl node-purge-ttl report-ttl]} :- {:node-ttl Period
                                                    :node-purge-ttl Period
                                                    :report-ttl Period
                                                    s/Keyword s/Any}
   ;; Later, the values might be maps, i.e. {:limit 1000}
   request :- clean-request-schema]
  (when-not (.isHeldByCurrentThread lock)
    (throw (IllegalStateException. (tru "cleanup lock is not already held"))))
  (let [request (reduce-clean-request (if (empty? request)
                                        clean-options  ; clean everything
                                        request))
        status (reduced-clean-request->status request)]
    (try
      (reset! clean-status status)
      (when (request "expire_nodes")
        (time! (:node-expiration-time admin-metrics)
               (auto-expire-nodes! node-ttl db))
        (counters/inc! (:node-expirations admin-metrics)))
      (when-let [opts (request "purge_nodes")]
        (time! (:node-purge-time admin-metrics)
               (purge-nodes! node-purge-ttl opts db))
        (counters/inc! (:node-purges admin-metrics)))
      (when (request "purge_reports")
        (time! (:report-purge-time admin-metrics)
               (sweep-reports! report-ttl db))
        (counters/inc! (:report-purges admin-metrics)))
      (when (request "gc_packages")
        (time! (:package-gc-time admin-metrics)
               (gc-packages! db))
        (counters/inc! (:package-gcs admin-metrics)))
      ;; It's important that this go last to ensure anything referencing
      ;; an env or resource param is purged first.
      (when (request "other")
        (time! (:other-clean-time admin-metrics)
               (garbage-collect! db))
        (counters/inc! (:other-cleans admin-metrics)))
      (finally
        (clear-clean-status!)))))

(defn- clean-puppetdb
  "Implements the PuppetDBServer clean method, see the protocol for
  further information."
  [context config what]
  (let [lock (:clean-lock context)]
    (when (.tryLock lock)
      (try
        (clean-up (get-in context [:shared-globals :scf-write-db])
                  lock
                  (:database config)
                  what)
        true
        (finally
          (.unlock lock))))))

(defn- delete-node-from-puppetdb [context certname]
  "Implements the PuppetDBServer delete-node method, see the protocol
   for further information"
  (jdbc/with-transacted-connection (get-in context [:shared-globals :scf-write-db])
    (scf-store/delete-certname! certname)))

(defn maybe-check-for-updates
  [config read-db job-pool]
  (if (conf/foss? config)
    (let [checkin-interval-millis (* 1000 60 60 24)] ; once per day
      (every checkin-interval-millis #(-> config
                                          conf/update-server
                                          (version/check-for-updates! read-db))
             job-pool
             :desc "A reoccuring job to checkin the PuppetDB version"))
    (log/debug (trs "Skipping update check on Puppet Enterprise"))))

(def stop-gc-wait-ms (constantly 5000))

(defn stop-puppetdb
  "Shuts down PuppetDB, releasing resources when possible.  If this is
  not a normal shutdown, emergency? must be set, which currently just
  produces a fatal level level log message, instead of info."
  [{:keys [stop-status] :as context}]
  (log/info (trs "Shutdown request received; puppetdb exiting."))
  (when-let [pool (:job-pool context)]
    (stop-and-reset-pool! pool))
  ;; Wait up to ~5s for https://bugs.openjdk.java.net/browse/JDK-8176254
  (swap! stop-status #(conj % :stopping))
  (if (utils/wait-for-ref-state stop-status (stop-gc-wait-ms) #(= % #{:stopping}))
    (log/info (trs "Periodic activities halted"))
    (log/info (trs "Forcibly terminating periodic activities")))
  (when-let [ds (get-in context [:shared-globals :scf-write-db :datasource])]
    (.close ds))
  (when-let [ds (get-in context [:shared-globals :scf-read-db :datasource])]
    (.close ds))
  (when-let [command-loader (:command-loader context)]
    (future-cancel command-loader))
  context)

;; A map of required postgres settings and their expected value.
;; PuppetDB will refuse to start unless ALL of these values
;; match the actual database configuration settings.
(def required-pg-settings
  {:standard_conforming_strings "on"})

(defn verify-database-setting
  "Given m, a map of database settings, verify 'expected', which has the form
  [setting-name expected-value]."
  [m [setting value]]
  (let [map-path [setting :setting]]
    (when-not (= (get-in m map-path) value)
      {setting {:expected value :actual (get-in m map-path)}})))

(defn request-database-settings []
  (jdbc/query "show all;"))

(defn verify-database-settings
  "Ensure the database configuration does not have any settings known
  to break PuppetDB. If an invalid database configuration is found, throws
  {:kind ::invalid-database-configuration :failed-validation failed-map}"
  [database-settings]
  (let [munged-settings (apply merge
                               (map (fn [h]
                                      {(keyword (:name h)) (dissoc h :name)})
                                    database-settings))
        invalid-settings (remove nil? (map #(verify-database-setting munged-settings %) required-pg-settings))]
    (when (seq invalid-settings)
      (throw (ex-info "One or more postgres settings failed validation"
                      {:kind ::invalid-database-configuration
                       :failed-validation (apply merge invalid-settings)})))))

(defn verify-database-version
  "Verifies that the available database version is acceptable.  Throws
  {:kind ::unsupported-database :current version :oldest version} if
  the current database is not supported."
  [config]
  (let [current (:version @sutils/db-metadata)
        oldest (get-in config [:database :min-required-version]
                       scf-store/oldest-supported-db)]
    (when (neg? (compare current oldest))
      (throw (ex-info "Database version too old"
                      {:kind ::unsupported-database
                       :current current
                       :oldest oldest})))
    @sutils/db-metadata))

(defn require-valid-db
  [config]
  (verify-database-version config)
  (verify-database-settings (request-database-settings)))

(defn require-current-schema
  []
  (require-valid-schema)
  (when-let [pending (seq (pending-migrations))]
    (let [m (str
             (trs "Database is not fully migrated and migration is disallowed.")
             (trs "  Missing migrations: {0}" (mapv first pending)))]
      (throw (ex-info m {:kind ::migration-required
                         :pending pending})))))

(defn prep-db
  [datasource config]
  (jdbc/with-db-connection datasource
    (require-valid-db config)
    (if (get-in config [:database :migrate?])
      (initialize-schema)
      (require-current-schema))))

(defn- require-db-connection-as [datasource migrator]
  (jdbc/with-db-connection datasource
    (let [current-user (jdbc/current-user)]
      (when-not (= current-user migrator)
        (throw
         (ex-info (format "Connected to database as %s, not migrator %s"
                          (pr-str current-user)
                          (pr-str migrator))
                  {:kind ::connected-as-wrong-user
                   :expected migrator
                   :actual current-user}))))))

(defn init-with-db
  "Performs all initialization operations requiring a database
  connection using a transient connection pool derived from the
  `write-db-config`.  Blocks until the initialization is complete, and
  then returns an unspecified value.  Throws exceptions on errors.
  Throws {:kind ::unsupported-database :current version :oldest
  version} if the current database is not supported. Throws
  {:kind ::invalid-database-configuration :failed-validation failed-map}
  if the database contains a disallowed setting."
  [write-db-config config]
  ;; A C-c (SIGINT) will close the pool via the shutdown hook, which
  ;; will then cause the pool connection to throw a (generic)
  ;; SQLException.
  (let [migrator (:migrator-username write-db-config)]
    (with-open [db-pool (-> (assoc write-db-config
                                   :pool-name "PDBMigrationsPool"
                                   :connection-timeout 3000
                                   :user migrator
                                   :password (:migrator-password write-db-config))
                            (jdbc/make-connection-pool database-metrics-registry))]
      (let [runtime (Runtime/getRuntime)
            on-shutdown (doto (Thread.
                               #(try
                                  (.close db-pool)
                                  (catch Exception ex
                                    ;; Nothing to do but log it...
                                    (log/error ex (trs "Unable to close migration pool")))))
                          (.setName "PuppetDB migration pool closer"))]
        (.addShutdownHook runtime on-shutdown)
        (try
          (loop [i 0
                 last-ex nil]
            (let [result (try
                           (let [datasource {:datasource db-pool}]
                             (require-db-connection-as datasource migrator)
                             (prep-db datasource config))
                           true
                           (catch java.sql.SQLTransientConnectionException ex
                             ;; When coupled with the 3000ms timout, this
                             ;; is intended to log a duplicate message no
                             ;; faster than once per minute.
                             (when (or (not= (str ex) (str last-ex))
                                       (zero? (mod i 20)))
                               (log/error (str (trs "Will retry database connection after temporary failure: ")
                                               ex)))
                             ex))]
              (if (true? result)
                result
                (recur (inc i) result))))
          (finally
            (try
              (.removeShutdownHook runtime on-shutdown)
              (catch IllegalStateException ex
                ;; Ignore, because we're already shutting down.
                nil))))))))

(defn db-config->clean-request
  [config]
  (let [seconds-pos? (comp pos? to-seconds)]
    (filter identity
            [(when (some-> (:node-ttl config) seconds-pos?)
               "expire_nodes")
             (when (some-> (:node-purge-ttl config) seconds-pos?)
               (let [limit (:node-purge-gc-batch-limit config)]
                 (if (zero? limit)
                            "purge_nodes"
                            ["purge_nodes" {:batch_limit limit}])))
             (when (some-> (:report-ttl config) seconds-pos?)
               "purge_reports")
             "gc_packages"
             "other"])))

(defn collect-garbage
  [db clean-lock config clean-request]
  (.lock clean-lock)
  (try
    (try
      (clean-up db clean-lock config clean-request)
      (catch Exception ex
        (log/error ex)))
    (finally
      (.unlock clean-lock))))

(defn coordinate-gc-with-shutdown
  [write-db clean-lock database request stop-status]
  ;; This function assumes it will never be called concurrently with
  ;; itself, so that it's ok to just conj/disj below, instead of
  ;; tracking an atomic count or similar.
  (let [update-if-not-stopping #(if (:stopping %)
                                  %
                                  (conj % :collecting-garbage))]
    (try
      (when (:collecting-garbage (swap! stop-status update-if-not-stopping))
        (collect-garbage write-db clean-lock database request))
      (finally
        (swap! stop-status disj :collecting-garbage)))))

(defn maybe-send-cmd-event!
  "Put a map with identifying information about an enqueued command on the
   cmd-event-chan. Valid :kind values are: ::command/ingested ::command/processed"
  [emit-cmd-events? cmd-event-ch cmdref kind]
  (when emit-cmd-events?
    (async/>!! cmd-event-ch (queue/make-cmd-event cmdref kind))))

(defn check-schema-version
  [desired-schema-version context db service shutdown-on-error]
  {:pre [(integer? desired-schema-version)]}
  (when-not (= #{:stopping} @(:stop-status context))
    (let [schema-version (-> (jdbc/with-transacted-connection db
                               (jdbc/query "select max(version) from schema_migrations"))
                             first
                             :max)]
      (when-not (= schema-version desired-schema-version)
        (let [ex-msg (cond
                       (> schema-version desired-schema-version)
                       (str
                        (trs "Please upgrade PuppetDB: ")
                        (trs "your database contains schema migration {0} which is too new for this version of PuppetDB."
                             schema-version))

                       (< schema-version desired-schema-version)
                       (str
                        (trs "Please run PuppetDB with the migrate? option set to true to upgrade your database. ")
                        (trs "The detected migration level {0} is out of date." schema-version))

                       :else
                       (throw (Exception. "Unknown state when checking schema versions")))]
          (shutdown-on-error (service-id service)
                             #(throw (ex-info ex-msg {:kind ::schema-mismatch}))))))))

(defn start-puppetdb
  "Throws {:kind ::unsupported-database :current version :oldest version} if
  the current database is not supported. If a database setting is configured
  incorrectly, throws {:kind ::invalid-database-configuration :failed-validation failed-map}"
  [context config service get-registered-endpoints shutdown-on-error]
  (let [{:keys [developer jetty
                database read-database
                puppetdb command-processing
                emit-cmd-events?]} config
        {:keys [pretty-print max-enqueued]} developer
        {:keys [gc-interval node-purge-ttl schema-check-interval]} database
        {:keys [disable-update-checking]} puppetdb
        {:keys [cmd-event-mult cmd-event-ch]} context]

    (when-let [v (version/version)]
      (log/info (trs "PuppetDB version {0}" v)))

    (init-with-db database config)

    (let [read-db (jdbc/pooled-datasource (assoc read-database
                                                 :read-only? true
                                                 :pool-name "PDBReadPool"
                                                 :expected-schema desired-schema-version)
                                          database-metrics-registry)]

      (let [population-registry (get-in metrics/metrics-registries [:population :registry])]
        (pop/initialize-population-metrics! population-registry read-db))

      ;; Error handling here?
      (let [stockdir (conf/stockpile-dir config)
            command-chan (async/chan
                          (queue/sorted-command-buffer
                           max-enqueued
                           (fn [cmd ver] (cmd/update-counter! :invalidated cmd ver inc!))
                           (fn [cmd ver] (cmd/update-counter! :ignored cmd ver inc!))))
            emit-cmd-events? (or (conf/pe? config) emit-cmd-events?)
            maybe-send-cmd-event! (partial maybe-send-cmd-event! emit-cmd-events? cmd-event-ch)
            [q load-messages] (queue/create-or-open-stockpile (conf/stockpile-dir config)
                                                              maybe-send-cmd-event!
                                                              cmd-event-ch)
            dlo (dlo/initialize (get-path stockdir "discard")
                                (get-in metrics-registries
                                        [:dlo :registry]))
            clean-lock (ReentrantLock.)
            command-loader (when load-messages
                             (future
                               (load-messages command-chan cmd/inc-cmd-depth)))]

        ;; send the queue-loaded cmd-event if the command-loader didn't
        (when-not command-loader
          (async/>!! cmd-event-ch {:kind ::queue/queue-loaded}))

        ;; Pretty much this helper just knows our job-pool and gc-interval
        (let [job-pool (mk-pool)
              gc-interval-millis (to-millis gc-interval)
              write-db (jdbc/pooled-datasource (assoc database
                                                      :pool-name "PDBWritePool"
                                                      :expected-schema desired-schema-version)
                                               database-metrics-registry)]
          (when-not disable-update-checking
            (maybe-check-for-updates config read-db job-pool))
          (when (pos? schema-check-interval)
            (interspaced schema-check-interval
                         #(check-schema-version desired-schema-version
                                                context
                                                read-db
                                                service
                                                shutdown-on-error)
                         job-pool))
          (when (pos? gc-interval-millis)
            (let [request (db-config->clean-request database)]
              (interspaced gc-interval-millis
                           #(coordinate-gc-with-shutdown write-db clean-lock
                                                         database request
                                                         (:stop-status context))
                           job-pool)))
          (assoc context
                 :job-pool job-pool
                 :shared-globals {:scf-read-db read-db
                                  :scf-write-db write-db
                                  :pretty-print pretty-print
                                  :q q
                                  :dlo dlo
                                  :node-purge-ttl node-purge-ttl
                                  :command-chan command-chan
                                  :cmd-event-mult cmd-event-mult
                                  :maybe-send-cmd-event! maybe-send-cmd-event!}
                 :clean-lock clean-lock
                 :command-loader command-loader))))))

(defn db-unsupported-msg
  "Returns a message describing which databases are supported."
  [current oldest]
  (trs "PostgreSQL {0}.{1} is no longer supported.  Please upgrade to {2}.{3}."
       (first current)
       (second current)
       (first oldest)
       (second oldest)))

(defn invalid-conf-msg
  [invalid-settings]
  (str (trs "Invalid database configuration settings: ")
       (str/join ", "
                  ;; This is used to construct a list of invalid database settings
                  ;;
                  ;; 0 : The name of a database setting
                  ;; 1 : The expected value of that database setting
                  ;; 2 : The actual value of that database setting
                  (map (fn [[k v]] (trs "''{0}'' (expected ''{1}'', got ''{2}'')" (name k) (:expected v) (:actual v)))
                       invalid-settings))))

(defn start-puppetdb-or-shutdown
  [context config service get-registered-endpoints shutdown-on-error]
  {:pre [(map? context)
         (map? config)]
   :post [(map? %)]}
  (try
    (start-puppetdb context config service get-registered-endpoints shutdown-on-error)
    (catch ExceptionInfo ex
      (let [{:keys [kind] :as data} (ex-data ex)
            stop (fn [msg]
                   (let [msg (utils/attention-msg msg)]
                     (utils/println-err msg)
                     (log/error msg)
                     ;; This will become a custom exit request once
                     ;; trapperkeeper supports it.
                     (shutdown-on-error
                      (service-id service)
                      #(throw ex))))]
        (case kind
          ::unsupported-database
          (stop (db-unsupported-msg (:current data) (:oldest data)))

          ::invalid-database-configuration
          (stop (invalid-conf-msg (:failed-validation data)))

          ::migration-required
          (stop (.getMessage ex))

          ;; Unrecognized -- pass it on.
          (throw ex))))))

(defprotocol PuppetDBServer
  (shared-globals [this])
  (set-url-prefix [this url-prefix])
  (query [this version query-expr paging-options row-callback-fn]
    "Call `row-callback-fn' for matching rows.  The `paging-options' should
    be a map containing :order_by, :offset, and/or :limit.")
  (clean [this] [this what]
    "Performs maintenance.  If specified, what requests a subset of
    the normal operations, and must itself be a subset of
    #{\"expire_nodes\" \"purge_nodes\" \"purge_reports\" \"other\"}.
    If what is not specified or is empty, performs all maintenance.
    Returns false if some kind of maintenance was already in progress,
    true otherwise.  Although the latter does not imply that all of
    the operations were successful; consult the logs for more
    information.")
  (cmd-event-mult [this]
    "Returns a core.async mult to which
     {:kind <::ingested|::processed|::queue/queue-loaded>} maps are sent
     when emit-cmd-events? is set to true. In the case of
     {:kind <::ingested|::processed>} the map will contain :entity, :certname,
     and :producer-ts entries for each command PDB has either ingested or
     finished processing. The {:kind ::queue/queue-loaded} map is used to
     indicate that the queue/message-loader has finished loading any existing
     commands from the stockpile queue when PDB starts up.")
  (delete-node [this certname]
    "Immediately delete all data for the provided certname"))

(defservice puppetdb-service
  "Defines a trapperkeeper service for PuppetDB; this service is responsible
  for initializing all of the PuppetDB subsystems and registering shutdown hooks
  that trapperkeeper will call on exit."
  PuppetDBServer
  [[:DefaultedConfig get-config]
   [:WebroutingService add-ring-handler get-registered-endpoints]
   [:ShutdownService shutdown-on-error]]
  (init [this context]

        (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
          (jmx-reporter/start reporter))
        (let [cmd-event-ch (async/chan 1000)
              cmd-event-mult (async/mult cmd-event-ch)]
          (assoc context
                 :cmd-event-ch cmd-event-ch
                 :cmd-event-mult cmd-event-mult
                 :url-prefix (atom nil)
                 ;; This coordination is needed until we no longer
                 ;; support jdk < 10.
                 ;; https://bugs.openjdk.java.net/browse/JDK-8176254
                 :stop-status (atom #{}))))
  (start
   [this context]
   (start-puppetdb-or-shutdown context (get-config) this
                               get-registered-endpoints
                               shutdown-on-error))

  (stop [this context]
        (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
          (jmx-reporter/stop reporter))
        (let [{:keys [cmd-event-mult cmd-event-ch]} context]
          (when cmd-event-mult
            (async/untap-all cmd-event-mult))
          (when cmd-event-ch
            (async/close! cmd-event-ch)))
        (stop-puppetdb context))

  (set-url-prefix [this url-prefix]
                  (let [old-url-prefix (:url-prefix (service-context this))]
                    (when-not (compare-and-set! old-url-prefix nil url-prefix)
                      (throw+ {:url-prefix old-url-prefix
                               :new-url-prefix url-prefix
                               :type ::url-prefix-already-set}
                              (format "Attempt to set url-prefix to %s when it's already been set to %s" url-prefix @old-url-prefix)))))
  (shared-globals [this]
                  (:shared-globals (service-context this)))
  (query [this version query-expr paging-options row-callback-fn]
         (let [sc (service-context this)
               query-options (-> (get sc :shared-globals)
                                 (select-keys [:scf-read-db :warn-experimental :node-purge-ttl])
                                 (assoc :url-prefix @(get sc :url-prefix)))]
           (qeng/stream-query-result version
                                     query-expr
                                     paging-options query-options
                                     row-callback-fn)))

  (clean [this] (clean this #{}))
  (clean [this what] (clean-puppetdb (service-context this) (get-config) what))

  (delete-node [this certname]
               (delete-node-from-puppetdb (service-context this) certname))

  (cmd-event-mult [this] (-> this service-context :cmd-event-mult)))

(defn provide-services
  "Starts PuppetDB as a service via Trapperkeeper.  Augments TK's normal
  config parsing a bit."
  ([args] (provide-services args nil))
  ([args {:keys [upgrade-and-exit?]}]
   (let [hook (if upgrade-and-exit?
                (fn [f args]
                  (assoc-in (#'conf/hook-tk-parse-config-data f args)
                            [:global :upgrade-and-exit?]
                            true))
                #'conf/hook-tk-parse-config-data)]
     (rh/add-hook #'puppetlabs.trapperkeeper.config/parse-config-data hook))
   (apply tk/main args)))
