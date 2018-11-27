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
  (:require [clj-time.core :refer [ago]]
            [clojure.java.io :as io]
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
            [puppetlabs.puppetdb.cli.tk-util :refer [run-tk-cli-cmd]]
            [puppetlabs.puppetdb.cli.util :refer [exit]]
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
            [puppetlabs.puppetdb.scf.migrate :refer [migrate! indexes!]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.time :refer [to-seconds to-millis parse-period
                                              format-period period?]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.core :refer [defservice] :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]
            [robert.hooke :as rh]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.i18n.core :refer [trs tru]])
  (:import [java.util.concurrent.locks ReentrantLock]
           [org.joda.time Period]))

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
  (try
    (kitchensink/demarcate
     (format "sweep of stale resource events (threshold: %s)"
             (format-period resource-events-ttl))
     (jdbc/with-transacted-connection db
       (scf-store/delete-resource-events-older-than! (ago resource-events-ttl))))
    (catch Exception e
      (log/error e (trs "Error while sweeping resource events")))))

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
   :resource-events-purges (counter admin-metrics-registry "resource-event-purges")
   :package-gcs (counter admin-metrics-registry "package-gcs")
   :other-cleans (counter admin-metrics-registry "other-cleans")

   :node-expiration-time (timer admin-metrics-registry ["node-expiration-time"])
   :node-purge-time (timer admin-metrics-registry ["node-purge-time"])
   :report-purge-time (timer admin-metrics-registry ["report-purge-time"])
   :resource-events-purge-time (timer admin-metrics-registry ["resource-events-purge-time"])
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
      (when (request "purge_resource_events")
        (time! (:resource-events-purge-time admin-metrics)
               (gc-resource-events! resource-events-ttl db))
        (counters/inc! (:resource-events-purges admin-metrics)))
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

(defn initialize-schema
  "Ensures the database is migrated to the latest version, and returns
  true iff any migrations were run.  Throws
  {:type ::unsupported-database :current version :oldest version} if
  the current database is not supported."
  [db-conn-pool config]
  (jdbc/with-db-connection db-conn-pool
    (let [current (:version @sutils/db-metadata)
          oldest (get-in config [:database :min-required-version]
                           scf-store/oldest-supported-db)]
      (when (neg? (compare current oldest))
        (throw+ {:type ::unsupported-database
                 :current current
                 :oldest oldest}))
      @sutils/db-metadata
      (let [migrated? (migrate! db-conn-pool)]
        (indexes! config)
        migrated?))))

(defn init-with-db
  "All initialization operations needing a database connection should
  happen here. This function creates a connection pool using
  `write-db-config` that will hang until it is able to make a
  connection to the database. This covers the case of the database not
  being fully started when PuppetDB starts. This connection pool will
  be opened and closed within the body of this function.  Returns true
  iff any migrations were run.  Throws
  {:type ::unsupported-database :current version :oldest version} if the
  current database is not supported."
  [write-db-config config]
  (loop [db-spec (assoc write-db-config
                        ;; Block waiting to grab a connection
                        :connection-timeout 15000
                        :pool-name "PDBMigrationsPool")]
    (if-let [result
             (try
               (with-open [init-db-pool (jdbc/make-connection-pool db-spec
                                                                   database-metrics-registry)]
                 (let [db-pool-map {:datasource init-db-pool}]
                   (initialize-schema db-pool-map config)
                   ::success))
               (catch java.sql.SQLTransientConnectionException e
                 (log/error e (trs "Error while attempting to create connection pool"))))]
      result
      (recur db-spec))))

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

(defn start-puppetdb
  "Throws {:type ::unsupported-database :current version :oldest version} if
  the current database is not supported."
  [context config service get-registered-endpoints upgrade-and-exit?]
  {:pre [(map? context)
         (map? config)]
   :post [(map? %)]}

  (let [{:keys [database developer read-database]} config
        write-db (-> (assoc database :pool-name "PDBWritePool")
                     (jdbc/pooled-datasource database-metrics-registry))
        read-db (-> (assoc read-database
                           :pool-name "PDBReadPool"
                           :read-only? true)
                    (jdbc/pooled-datasource database-metrics-registry))
        globals {:scf-read-db read-db
                 :scf-write-db write-db
                 :pretty-print (:pretty-print developer)}]

    (when-let [v (version/version)]
      (log/info (trs "PuppetDB version {0}" v)))
    (init-with-db database config)

    (if upgrade-and-exit?
      (assoc context :shared-globals globals)
      (do
        (let [population-registry (get-in metrics/metrics-registries [:population :registry])]
          (pop/initialize-population-metrics! population-registry read-db))

        ;; Error handling here?
        (let [stockdir (conf/stockpile-dir config)
              command-chan (async/chan
                            (queue/sorted-command-buffer
                             (:max-enqueued developer)
                             #(cmd/update-counter! :invalidated %1 %2 inc!)))
              [q load-messages] (queue/create-or-open-stockpile (conf/stockpile-dir config))
              globals (assoc globals
                             :q q
                             :dlo (dlo/initialize (get-path stockdir "discard")
                                                  (get-in metrics-registries
                                                          [:dlo :registry]))
                             :command-chan command-chan)
              clean-lock (ReentrantLock.)
              command-loader (when load-messages
                               (future
                                 (load-messages command-chan cmd/inc-cmd-depth)))]

          ;; Pretty much this helper just knows our job-pool and gc-interval
          (let [job-pool (mk-pool)
                gc-interval-millis (to-millis (:gc-interval database))]
            (when-not (get-in config [:puppetdb :disable-update-checking])
              (maybe-check-for-updates config read-db job-pool))
            (when (pos? gc-interval-millis)
              (let [request (db-config->clean-request database)]
                (interspaced gc-interval-millis
                             #(coordinate-gc-with-shutdown write-db clean-lock
                                                           database request
                                                           (:stop-status context))
                             job-pool)))
            (assoc context
                   :job-pool job-pool
                   :shared-globals globals
                   :clean-lock clean-lock
                   :command-loader command-loader)))))))

(defn db-unsupported-msg
    "Returns a message describing which databases are supported."
    [current oldest]
    (trs "PostgreSQL {0}.{1} is no longer supported.  Please upgrade to {2}.{3}."
         (first current)
         (second current)
         (first oldest)
         (second oldest)))

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
    information."))

(defservice puppetdb-service
  "Defines a trapperkeeper service for PuppetDB; this service is responsible
  for initializing all of the PuppetDB subsystems and registering shutdown hooks
  that trapperkeeper will call on exit."
  PuppetDBServer
  [[:DefaultedConfig get-config]
   [:WebroutingService add-ring-handler get-registered-endpoints]
   [:ShutdownService request-shutdown shutdown-on-error]]
  (init [this context]

        (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
          (jmx-reporter/start reporter))
        (assoc context
               :url-prefix (atom nil)
               ;; This coordination is needed until we no longer
               ;; support jdk < 10.
               ;; https://bugs.openjdk.java.net/browse/JDK-8176254
               :stop-status (atom #{})))
  (start [this context]
         (try+
          (let [config (get-config)
                upgrade? (get-in config [:global :upgrade-and-exit?])
                context (start-puppetdb context config this
                                        get-registered-endpoints upgrade?)]
            (when upgrade?
              ;; start-puppetdb has finished the migrations, which is
              ;; all we needed to do.
              (request-shutdown))
            context)
          (catch [:type ::unsupported-database] {:keys [current oldest]}
            (let [msg (db-unsupported-msg current oldest)]
              (utils/println-err (str (trs "error: ") msg))
              (log/error msg)
              ;; Until TK-445 is resolved, we won't be able to avoid the
              ;; backtrace on exit this causes.
              (shutdown-on-error (service-id this)
                                 #(throw (Exception. msg)))))))

  (stop [this context]
        (doseq [{:keys [reporter]} (vals metrics/metrics-registries)]
          (jmx-reporter/stop reporter))
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
                                 (select-keys [:scf-read-db :warn-experimental])
                                 (assoc :url-prefix @(get sc :url-prefix)))]
           (qeng/stream-query-result version
                                     query-expr
                                     paging-options query-options
                                     row-callback-fn)))

  (clean [this] (clean this #{}))
  (clean [this what] (clean-puppetdb (service-context this) (get-config) what)))

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
