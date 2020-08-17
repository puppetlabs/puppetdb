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
  (:refer-clojure :exclude (with-open))
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [metrics.counters :as counters :refer [counter inc!]]
            [metrics.gauges :refer [gauge-fn]]
            [metrics.timers :refer [time! timer]]
            [metrics.reporters.jmx :as jmx-reporter]
            [murphy :refer [try! with-final]]
            [overtone.at-at :refer [mk-pool every interspaced stop-and-reset-pool!]]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.tk-util :refer [run-tk-cli-cmd]]
            [puppetlabs.puppetdb.cli.util :refer [exit err-exit-status]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.meta.version :as version]
            [puppetlabs.puppetdb.metrics.core :as metrics
             :refer [metrics-registries]]
            [puppetlabs.puppetdb.utils.metrics :as mutils]
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
            [puppetlabs.puppetdb.utils :as utils
             :refer [call-unless-shutting-down
                     exceptional-shutdown-requestor
                     throw-if-shutdown-pending
                     with-monitored-execution
                     with-nonfatal-exceptions-suppressed]]
            [puppetlabs.trapperkeeper.core :refer [defservice] :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [robert.hooke :as rh]
            [schema.core :as s]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.puppetdb.withopen :refer [with-open]])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io Closeable)
   [java.util.concurrent.locks ReentrantLock]
   [org.joda.time Period]))

(defn stop-reporters [registries]
  (letfn [(stop [[registry & registries]]
            (when registry
              (try!
               (stop registries)
               (finally
                 ;; Not certain whether :reporter can be nil
                 (some-> (:reporter registry) jmx-reporter/stop)))))]
    (stop (vals registries))))

(defn init-puppetdb
  [context]
  (with-final [_ metrics/metrics-registries :error stop-reporters
               cmd-event-ch (async/chan 1000) :error async/close!
               cmd-event-mult (async/mult cmd-event-ch) :error async/untap-all]
    (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
      (jmx-reporter/start reporter))
    (assoc context
           :cmd-event-ch cmd-event-ch
           :cmd-event-mult cmd-event-mult
           :url-prefix (atom nil)
           ;; This coordination is needed until we no longer
           ;; support jdk < 10.
           ;; https://bugs.openjdk.java.net/browse/JDK-8176254
           :stop-status (atom {})
           :shutdown-request (atom nil))))

(def database-metrics-registry (get-in metrics/metrics-registries [:database :registry]))

(def clean-options
  #{"expire_nodes" "purge_nodes" "purge_reports" "gc_packages" "purge_resource_events" "other"})

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
             "purge_resource_events" "purging-resource-events"
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
  "Delete resource-events entries which are older than than `resource-events-ttl`."
  [resource-events-ttl db]
  {:pre [(map? db)
         (period? resource-events-ttl)]}
  ;; apply a day floor on this - we can't be more granular than a day here.
  (let [rounded-date (-> (ago resource-events-ttl)
                         (.dayOfYear)
                         (.roundFloorCopy))]
   (try
     (kitchensink/demarcate
      (format "sweep of stale resource events (threshold: %s)"
              rounded-date)
      (jdbc/with-transacted-connection db
                                       (scf-store/delete-resource-events-older-than! rounded-date)))
     (catch Exception e
       (log/error e (trs "Error while sweeping resource events"))))))

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

(def admin-metrics (atom nil))

(defn create-admin-metrics [prefix]
  (let [pname #(or (when prefix (str prefix "." %)) %)
        admin-metrics
        {;;"expiring-nodes" | "expiring-nodes purging-reports" | ...
         :cleaning (gauge-fn admin-metrics-registry [(pname "cleaning")]
                             #(deref clean-status))
         :node-expirations (counter admin-metrics-registry [(pname "node-expirations")])
         :node-purges (counter admin-metrics-registry [(pname "node-purges")])
         :report-purges (counter admin-metrics-registry [(pname "report-purges")])
         :resource-events-purges (counter admin-metrics-registry [(pname "resource-event-purges")])
         :package-gcs (counter admin-metrics-registry [(pname "package-gcs")])
         :other-cleans (counter admin-metrics-registry [(pname "other-cleans")])
         :node-expiration-time (timer admin-metrics-registry [(pname "node-expiration-time")])
         :node-purge-time (timer admin-metrics-registry [(pname "node-purge-time")])
         :report-purge-time (timer admin-metrics-registry [(pname "report-purge-time")])
         :resource-events-purge-time (timer admin-metrics-registry [(pname "resource-events-purge-time")])
         :package-gc-time (timer admin-metrics-registry [(pname "package-gc-time")])
         :other-clean-time (timer admin-metrics-registry [(pname "other-clean-time")])}]
    (mutils/prefix-metric-keys prefix admin-metrics)))

(defn init-admin-metrics [scf-write-dbs]
  (->> scf-write-dbs
       (map mutils/get-db-name)
       (map create-admin-metrics)
       (apply merge)
       (reset! admin-metrics)))

(defn- clear-clean-status!
  "Clears the clean status (as a separate function to support tests)."
  []
  (reset! clean-status ""))

;; FIXME: db name in all gc logging?

(defn-validated clean-up
  "Cleans up the resources specified by request, or everything if
  request is empty?."
  [db
   lock :- ReentrantLock
   {:keys [node-ttl node-purge-ttl
           report-ttl resource-events-ttl]} :- {:node-ttl Period
                                                :node-purge-ttl Period
                                                :report-ttl Period
                                                :resource-events-ttl Period
                                                s/Keyword s/Any}
   ;; Later, the values might be maps, i.e. {:limit 1000}
   request :- clean-request-schema]
  (when-not (.isHeldByCurrentThread lock)
    (throw (IllegalStateException. (tru "cleanup lock is not already held"))))
  (let [request (reduce-clean-request (if (empty? request)
                                        clean-options  ; clean everything
                                        request))
        status (reduced-clean-request->status request)
        prefix (mutils/get-db-name db)
        get-metric (fn [metric]
                     ((mutils/maybe-prefix-key prefix metric) @admin-metrics))]
    (try
      (reset! clean-status status)
      (when (request "expire_nodes")
        (time! (get-metric :node-expiration-time)
               (auto-expire-nodes! node-ttl db))
        (counters/inc! (get-metric :node-expirations)))
      (when-let [opts (request "purge_nodes")]
        (time! (get-metric :node-purge-time)
               (purge-nodes! node-purge-ttl opts db))
        (counters/inc! (get-metric :node-purges)))
      (when (request "purge_reports")
        (time! (get-metric :report-purge-time)
               (sweep-reports! report-ttl db))
        (counters/inc! (get-metric :report-purges)))
      (when (request "gc_packages")
        (time! (get-metric :package-gc-time)
               (gc-packages! db))
        (counters/inc! (get-metric :package-gcs)))
      (when (request "purge_resource_events")
        (time! (get-metric :resource-events-purge-time)
               (gc-resource-events! resource-events-ttl db))
        (counters/inc! (get-metric :resource-events-purges)))
      ;; It's important that this go last to ensure anything referencing
      ;; an env or resource param is purged first.
      (when (request "other")
        (time! (get-metric :other-clean-time)
               (garbage-collect! db))
        (counters/inc! (get-metric :other-cleans)))
      (finally
        (clear-clean-status!)))))

(defn- clean-puppetdb
  "Implements the PuppetDBServer clean method, see the protocol for
  further information."
  [context what]
  ;; For now, just serialize them.
  ;; FIXME: improve request results wrt multiple databases?
  (let [lock (:clean-lock context)]
    (when (.tryLock lock)
      (try
        (loop [[db & dbs] (get-in context [:shared-globals :scf-write-dbs])
               [cfg & cfgs] (get-in context [:shared-globals :scf-write-db-cfgs])
               ex nil]
          (if-not db
            (if-not ex
              true
              (throw ex))
            (let [ex (try
                       (clean-up db lock cfg what)
                       ex
                       (catch Exception db-ex
                         (when ex (.addSuppressed ex db-ex))
                         (or ex db-ex)))]
              (recur dbs cfgs ex))))
        (finally
          (.unlock lock))))))

(defn- delete-node-from-puppetdb [context certname]
  "Implements the PuppetDBServer delete-node method, see the protocol
   for further information"
  (loop [[db & dbs] (get-in context [:shared-globals :scf-write-dbs])
         ex nil]
    (if-not db
      (when ex (throw ex))
      (let [ex (try
                 (jdbc/with-transacted-connection db
                   (scf-store/delete-certname! certname))
                 ex
                 (catch Exception db-ex
                   (when ex (.addSuppressed ex db-ex))
                   (or ex db-ex)))]
        (recur dbs ex)))))

(defn maybe-check-for-updates
  [config read-db job-pool shutdown-for-ex]
  (if (conf/foss? config)
    (let [checkin-interval-millis (* 1000 60 60 24)] ; once per day
      (every checkin-interval-millis
             #(with-nonfatal-exceptions-suppressed
                (with-monitored-execution shutdown-for-ex
                  (-> config
                      conf/update-server
                      (version/check-for-updates! read-db))))
             job-pool
             :desc "A reoccuring job to checkin the PuppetDB version"))
    (log/debug (trs "Skipping update check on Puppet Enterprise"))))

(def stop-gc-wait-ms (constantly 5000))

(defn ready-to-stop? [{:keys [stopping collecting-garbage]}]
  (and stopping (not (seq collecting-garbage))))

(defn close-write-dbs [dbs]
  (loop [[db & dbs] dbs
         ex nil]
    (if-not db
      (when ex (throw ex))
      (let [ex (try
                 (when-let [ds (:datasource db)]
                   (.close ^Closeable ds))
                 ex
                 (catch Exception db-ex
                   (when ex (.addSuppressed ex db-ex))
                   (or ex db-ex)))]
        (recur dbs ex)))))

(defn stop-puppetdb
  "Shuts down PuppetDB, releasing resources when possible.  If this is
  not a normal shutdown, emergency? must be set, which currently just
  produces a fatal level level log message, instead of info."
  [{:keys [stop-status] :as context}]
  ;; Must also handle a nil context.
  (try!
   (log/info (trs "Shutdown request received; puppetdb exiting."))
   context
   (finally (some-> (:job-pool context) stop-and-reset-pool!))
   (finally
     (when stop-status
       ;; Wait up to ~5s for https://bugs.openjdk.java.net/browse/JDK-8176254
       (swap! stop-status assoc :stopping true)
       (if (utils/await-ref-state stop-status ready-to-stop? (stop-gc-wait-ms) false)
         (log/info (trs "Periodic activities halted"))
         (log/info (trs "Forcibly terminating periodic activities")))))
   (finally (close-write-dbs (get-in context [:shared-globals :scf-write-dbs])))
   (finally (some-> (get-in context [:shared-globals :scf-read-db :datasource])
                    .close))
   (finally (some-> (:command-loader context) future-cancel))
   (finally (some-> (:cmd-event-ch context) async/close!))
   (finally (some-> (:cmd-event-mult context) async/untap-all))
   (finally (stop-reporters metrics/metrics-registries))))

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
  [min-version-override]
  (let [{current :version :as meta} (sutils/db-metadata)
        oldest (or min-version-override scf-store/oldest-allowed-db)
        supported scf-store/oldest-supported-db]
    (when (neg? (compare current oldest))
      (throw (ex-info "Database version too old"
                      {:kind ::unsupported-database
                       :current current
                       :oldest supported})))
    (when (neg? (compare current supported))
      (log/warn (trs "PostgreSQL {0}.{1} is deprecated. Please upgrade to PostgreSQL {2}"
                     (first current)
                     (second current)
                     (first supported))))
    meta))

(defn require-valid-db-server
  [min-version-override]
  (verify-database-version min-version-override)
  (verify-database-settings (request-database-settings)))

(defn require-current-schema
  [msg]
  (require-valid-schema)
  (when-let [pending (seq (pending-migrations))]
    (let [m (str msg (trs "  Missing migrations: {0}" (mapv first pending)))]
      (throw (ex-info m {:kind ::migration-required
                         :pending pending})))))

(defn require-valid-db [min-version-override]
  (require-valid-db-server min-version-override)
  (require-current-schema (trs "Database is not fully migrated.")))

(defn prep-db
  [{:keys [username migrator-username migrate min-required-version]}]
  (require-valid-db-server min-required-version)
  (if migrate
    (if (= username migrator-username)
      (initialize-schema)
      (initialize-schema username (jdbc/current-database)))
    (require-current-schema
     (trs "Database is not fully migrated and migration is disallowed."))))

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
  db-config.  Blocks until the initialization is complete, and
  then returns an unspecified value.  Throws exceptions on errors.
  Throws {:kind ::unsupported-database :current version :oldest
  version} if the current database is not supported. Throws
  {:kind ::invalid-database-configuration :failed-validation failed-map}
  if the database contains a disallowed setting."
  [db-name db-config]
  ;; A C-c (SIGINT) will close the pool via the shutdown hook, which
  ;; will then cause the pool connection to throw a (generic)
  ;; SQLException.
  (log/info (trs "Ensuring {0} database is up to date" name))
  (let  [migrator (:migrator-username db-config)]
    (with-open [db-pool (-> (assoc db-config
                                   :pool-name (if db-name
                                                (str "PDBMigrationsPool: " db-name)
                                                "PDBMigrationsPool")
                                   :connection-timeout 3000
                                   :rewrite-batched-inserts "true"
                                   :user migrator
                                   :password (:migrator-password db-config))
                            (jdbc/make-connection-pool database-metrics-registry))]
      (let [runtime (Runtime/getRuntime)
            on-shutdown (doto (Thread.
                               #(try
                                  (.close ^Closeable db-pool)
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
                             (jdbc/with-db-connection datasource
                               (prep-db db-config)))
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
             (when (some-> (:resource-events-ttl config) seconds-pos?)
               "purge_resource_events")
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

;; FIXME: with-logged-ex...

(defn coordinate-gc-with-shutdown
  [write-db clean-lock config request stop-status]
  ;; This function assumes it will never be called concurrently with
  ;; itself, so that it's ok to just conj/disj below, instead of
  ;; tracking an atomic count or similar.
  (let [thread (Thread/currentThread)
        update-if-not-stopping (fn [status]
                                 (if (:stopping status)
                                   status
                                   (update status :collecting-garbage
                                           ;; union not conj, so nil is ok
                                           #(set/union % #{thread}))))]
    (try
      (when (get-in (swap! stop-status update-if-not-stopping)
                    [:collecting-garbage thread])
        (collect-garbage write-db clean-lock config request))
      (catch Exception ex
        (log/error ex)
        (throw ex))
      (finally
        (swap! stop-status (fn [status]
                             (update status :collecting-garbage
                                     ;; Very important that this not
                                     ;; set :collecting-garbage to
                                     ;; #{}, so that existing code
                                     ;; that doesn't check (seq
                                     ;; status) will work.
                                     #(let [s (disj % thread)]
                                        (when (seq s) s)))))))))

(defn maybe-send-cmd-event!
  "Put a map with identifying information about an enqueued command on the
   cmd-event-chan. Valid :kind values are: ::command/ingested ::command/processed"
  [emit-cmd-events? cmd-event-ch cmdref kind]
  (when emit-cmd-events?
    (async/>!! cmd-event-ch (queue/make-cmd-event cmdref kind))))

(defn check-schema-version
  [desired-version stop-status db service request-shutdown]
  {:pre [(integer? desired-version)]}
  (when-not (:stopping @stop-status)
    (let [schema-version (-> (jdbc/with-transacted-connection db
                               (jdbc/query "select max(version) from schema_migrations"))
                             first
                             :max)
          stop (fn [msg status]
                 (log/error msg)
                 (request-shutdown {::tk/exit
                                    {:status status
                                     :messages [[msg *err*]]}}))]
      (when-not (= schema-version desired-version)
        (cond
          (> schema-version desired-version)
          (stop (str
                 (trs "Please upgrade PuppetDB: ")
                 (trs "your database contains schema migration {0} which is too new for this version of PuppetDB."
                      schema-version))
                (int \M))

          (< schema-version desired-version)
          (stop (str
                 (trs "Please run PuppetDB with the migrate option set to true to upgrade your database. ")
                 (trs "The detected migration level {0} is out of date." schema-version))
                (int \m))

          :else
          (throw (Exception. "Unknown state when checking schema versions")))))))

(defn init-queue [config send-event! cmd-event-ch shutdown-for-ex]
  (let [stockdir (conf/stockpile-dir config)
        cmd-ch (async/chan
                (queue/sorted-command-buffer
                 (get-in config [:developer :max-enqueued])
                 (fn [cmd ver] (cmd/update-counter! :invalidated cmd ver inc!))
                 (fn [cmd ver] (cmd/update-counter! :ignored cmd ver inc!))))
        [q load-messages] (queue/create-or-open-stockpile (conf/stockpile-dir config)
                                                          send-event!
                                                          cmd-event-ch)
        dlo (dlo/initialize (get-path stockdir "discard")
                            (get-in metrics-registries [:dlo :registry]))]
    ;; send the queue-loaded cmd-event if the command-loader didn't
    (when-not load-messages
      (async/>!! cmd-event-ch {:kind ::queue/queue-loaded}))
    {:q q
     :dlo dlo
     ;; Caller is responsible for calling future-cancel on this in the end.
     :command-chan cmd-ch
     :command-loader (when load-messages
                       (future
                         (with-monitored-execution shutdown-for-ex
                           (load-messages cmd-ch cmd/inc-cmd-depth))))}))

(defn init-write-dbs [databases]
  ;; FIXME: forbid subdb settings?
  (with-final [desired-schema (desired-schema-version)
               db-names (vec (keys databases))
               pools (atom []) :error #(close-write-dbs (deref %))]

    (doseq [name db-names
            :let [config (get databases name)
                  pool-name (if (::conf/unnamed config)
                              "PDBWritePool"
                              (str "PDBWritePool: " name))]]
      (swap! pools conj
             (-> config
                 (assoc :pool-name pool-name
                        :expected-schema desired-schema)
                 (jdbc/pooled-datasource database-metrics-registry))))
    {:write-db-names db-names
     :write-db-cfgs (mapv #(get databases %) db-names)
     :write-db-pools @pools}))

(defn init-metrics [read-db write-dbs]
  ;; metrics are registered on startup to account for cmd broadcast
  (scf-store/init-storage-metrics write-dbs)
  (init-admin-metrics write-dbs)
  (pop/initialize-population-metrics!
   (get-in metrics/metrics-registries [:population :registry])
   read-db))

(def allocate-at-startup-at-least-mb
  (when-let [mb (System/getenv "PDB_TEST_ALLOCATE_AT_LEAST_MB_AT_STARTUP")]
    (Long/parseLong mb)))

(defn start-schema-checks
  [context service job-pool request-shutdown db-configs db-pools shutdown-for-ex]
  (doseq [[{:keys [schema-check-interval] :as cfg} db] (map vector db-configs db-pools)
          :when (pos? schema-check-interval)]
    (interspaced schema-check-interval
                 (fn []
                   (with-nonfatal-exceptions-suppressed
                     (with-monitored-execution shutdown-for-ex
                       ;; Just for testing out of memory handling.
                       ;; See ./ext/test.  Intentionally done in the
                       ;; at-at task to also see that it works from a
                       ;; "background" thread.
                       (when allocate-at-startup-at-least-mb
                         (log/warn (trs "Allocating as requested: PDB_TEST_ALLOCATE_AT_LEAST_MB_AT_STARTUP={0}"
                                        (str allocate-at-startup-at-least-mb)))
                         (vec (repeatedly allocate-at-startup-at-least-mb
                                          #(long-array (* 1024 128))))) ;; ~1mb
                       (check-schema-version (desired-schema-version)
                                             (:stop-status context)
                                             db service request-shutdown))))
                 job-pool)))

(defn start-garbage-collection
  [{:keys [clean-lock stop-status] :as context}
   job-pool db-configs db-pools shutdown-for-ex]
  (doseq [[cfg db] (map vector db-configs db-pools)
          :let [interval (to-millis (:gc-interval cfg))]
          :when (pos? interval)]
    (let [request (db-config->clean-request cfg)]
      (interspaced interval
                   #(with-nonfatal-exceptions-suppressed
                      (with-monitored-execution shutdown-for-ex
                        (coordinate-gc-with-shutdown db clean-lock cfg request
                                                     stop-status)))
                   job-pool))))

(defn start-puppetdb
  "Throws {:kind ::unsupported-database :current version :oldest version} if
  the current database is not supported. If a database setting is configured
  incorrectly, throws {:kind ::invalid-database-configuration :failed-validation failed-map}"
  ;; Note: the unsupported-database-triggers-shutdown test relies on
  ;; the documented exception behavior and argument order.
  [context config service get-registered-endpoints request-shutdown
   upgrade-and-exit?]

  (when-let [v (version/version)]
    (log/info (trs "PuppetDB version {0}" v)))

  (let [{:keys [database developer read-database emit-cmd-events?]} config
        {:keys [cmd-event-mult cmd-event-ch]} context
        ;; Assume that the exception has already been reported.
        shutdown-for-ex (exceptional-shutdown-requestor request-shutdown nil 2)
        write-dbs-config (conf/write-databases config)
        emit-cmd-events? (or (conf/pe? config) emit-cmd-events?)
        maybe-send-cmd-event! (partial maybe-send-cmd-event! emit-cmd-events? cmd-event-ch)
        context (assoc context  ;; context may be augmented further below
                       :shared-globals {:pretty-print (:pretty-print developer)
                                        :node-purge-ttl (:node-purge-ttl database)
                                        :add-agent-report-filter (get-in config [:puppetdb :add-agent-report-filter])
                                        :cmd-event-mult cmd-event-mult
                                        :maybe-send-cmd-event! maybe-send-cmd-event!
                                        ;; FIXME: remove this if/when
                                        ;; we add immediate ::tk/exit
                                        ;; support to trapperkeeper.
                                        :shutdown-request (:shutdown-request context)}
                       :clean-lock (ReentrantLock.))]

    (when (> (count write-dbs-config) 1)
      (let [msg (trs "multiple write database support is experimental")])
      (binding [*out* *err*]
        (println
         (trs "WARNING: multiple write database support is experimental")))
      (log/warn (trs "multiple write database support is experimental")))

    (doseq [[name config] write-dbs-config]
      (init-with-db name config))

    (if upgrade-and-exit?
      context
      (with-final [read-db (-> (assoc read-database
                                      :pool-name "PDBReadPool"
                                      :expected-schema (desired-schema-version)
                                      :read-only? true)
                               (jdbc/pooled-datasource database-metrics-registry))
                   :error .close

                   _ (jdbc/with-db-connection read-db
                       (require-valid-db
                        (get-in config [:database :min-required-version])))

                   {:keys [command-chan command-loader dlo q]}
                   (init-queue config maybe-send-cmd-event! cmd-event-ch shutdown-for-ex)

                   _ command-loader :error #(some-> % future-cancel)
                   job-pool (mk-pool) :error stop-and-reset-pool!

                   {:keys [write-db-cfgs write-db-names write-db-pools]}
                   (init-write-dbs write-dbs-config)

                   _ write-db-pools :error close-write-dbs]

        (init-metrics read-db write-db-pools)

        (when-not (get-in config [:puppetdb :disable-update-checking])
          (maybe-check-for-updates config read-db job-pool shutdown-for-ex))

        (start-schema-checks context service job-pool request-shutdown
                             (cons read-database write-db-cfgs)
                             (cons read-db write-db-pools)
                             shutdown-for-ex)
        (start-garbage-collection context job-pool write-db-cfgs write-db-pools
                                  shutdown-for-ex)

        (-> context
            (assoc :job-pool job-pool
                   :command-loader command-loader
                   ::started? true)
            (update :shared-globals merge
                    {:command-chan command-chan
                     :dlo dlo
                     :maybe-send-cmd-event! maybe-send-cmd-event!
                     :q q
                     :scf-read-db read-db
                     :scf-write-dbs write-db-pools
                     :scf-write-db-cfgs write-db-cfgs
                     :scf-write-db-names write-db-names}))))))

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
  [context config service get-registered-endpoints request-shutdown]
  {:pre [(map? context)
         (map? config)]
   :post [(map? %)]}
  (try
    (let [upgrade? (get-in config [:global :upgrade-and-exit?])
          context (start-puppetdb context config service
                                  get-registered-endpoints
                                  request-shutdown
                                  (get-in config [:global :upgrade-and-exit?]))]
      (when upgrade?
        (request-shutdown {::tk/exit {:status 0}}))
      (reset! (:shutdown-request context) nil)
      context)
    (catch ExceptionInfo ex
      (let [{:keys [kind] :as data} (ex-data ex)
            stop (fn [msg status]
                   (if (zero? status)
                     (log/info msg)
                     (log/error msg))
                   (request-shutdown {::tk/exit
                                      {:status status
                                       :messages [[msg *err*]]}})
                   context)]
        (case kind
          ::unsupported-database
          (stop (db-unsupported-msg (:current data) (:oldest data)) err-exit-status)

          ::invalid-database-configuration
          (stop (invalid-conf-msg (:failed-validation data)) err-exit-status)

          ::migration-required (stop (.getMessage ex) (int \m))

          ::must-migrate-multiple-databases
          (stop (str "puppetdb: " (.getMessage ex) "\n") err-exit-status)

          ;; Unrecognized -- pass it on.
          (throw ex))))))

(defn shutdown-requestor
  "Returns a shim for TK's request-shutdown that also records the
  shutdown request in the service context as :shutdown-request."
  [request-shutdown service]
  ;; Changes to the argument order above will require changes to the
  ;; unsupported-database-triggers-shutdown test.
  (fn shutdown [opts]
    (let [{{:keys [status messages] :as exit-opts} ::tk/exit} opts]
      (assert (integer? status))
      (assert (every? string? (map first messages)))
      (some-> (:shutdown-request (service-context service))
              (reset! {:opts opts}))
      (request-shutdown opts))))

(defn throw-unless-initialized [context]
  (when-not (seq context)
    (throw (IllegalStateException. (trs "Service is uninitialized")))))

(defn throw-unless-started [context]
  (throw-unless-initialized context)
  (when-not (::started? context)
    (throw (IllegalStateException. (trs "Service has not started")))))

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
   [:ShutdownService get-shutdown-reason request-shutdown]]

  (init
   [this context]
   (call-unless-shutting-down
    "PuppetDB service init" (get-shutdown-reason) context
    #(init-puppetdb context)))

  (start
   [this context]
   (call-unless-shutting-down
    "PuppetDB service start" (get-shutdown-reason) context
    #(do
       ;; Some tests rely on keeping all the logic out of this function,
       ;; to test startup errors, etc.
       (start-puppetdb-or-shutdown context (get-config) this
                                   get-registered-endpoints
                                   (shutdown-requestor request-shutdown this)))))

  (stop [this context] (stop-puppetdb context))

  (set-url-prefix
   [this url-prefix]
   (throw-if-shutdown-pending (get-shutdown-reason))
   (let [context (service-context this)
         old-url-prefix (:url-prefix context)]
     (throw-unless-initialized context)
     (when-not (compare-and-set! old-url-prefix nil url-prefix)
       (throw
        (ex-info (format "Cannot set url-prefix to %s when it has already been set to %s"
                         url-prefix @old-url-prefix)
                 {:kind ::url-prefix-already-set
                  :url-prefix old-url-prefix
                  :new-url-prefix url-prefix})))))
  (shared-globals
   [this]
   (let [context (service-context this)]
     (throw-unless-started context)
     (throw-if-shutdown-pending (get-shutdown-reason))
     (:shared-globals context)))

  (query
   [this version query-expr paging-options row-callback-fn]
   (throw-if-shutdown-pending (get-shutdown-reason))
   (let [sc (service-context this)
         _ (throw-unless-started sc)
         query-options (-> (get sc :shared-globals)
                           (select-keys [:scf-read-db :warn-experimental :node-purge-ttl :add-agent-report-filter])
                           (assoc :url-prefix @(get sc :url-prefix)))]
     (qeng/stream-query-result version
                               query-expr
                               paging-options query-options
                               row-callback-fn)))

  (clean
   [this]
   (throw-unless-started (service-context this))
   (throw-if-shutdown-pending (get-shutdown-reason))
   (clean this #{}))

  (clean
   [this what]
   (throw-unless-started (service-context this))
   (throw-if-shutdown-pending (get-shutdown-reason))
   (clean-puppetdb (service-context this) what))

  (delete-node
   [this certname]
   (throw-unless-started (service-context this))
   (throw-if-shutdown-pending (get-shutdown-reason))
   (delete-node-from-puppetdb (service-context this) certname))

  (cmd-event-mult
   [this]
   (throw-unless-initialized (service-context this))
   (throw-if-shutdown-pending (get-shutdown-reason))
   (-> this service-context :cmd-event-mult)))

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
   (apply tk/main args)
   0))

(defn cli
  "Runs the services command as directed by the command line args and
  returns an appropriate exit status."
  ([args] (cli args nil))
  ([args {:keys [upgrade-and-exit?] :as opts}]
   (run-tk-cli-cmd #(provide-services args opts))))

(defn -main [& args]
  (exit (provide-services args)))
