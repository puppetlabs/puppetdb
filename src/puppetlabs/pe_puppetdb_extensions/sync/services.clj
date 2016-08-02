(ns puppetlabs.pe-puppetdb-extensions.sync.services
  (:import [org.joda.time Period]
           [java.net URI])
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [overtone.at-at :as atat]
            [metrics.reporters.jmx :as jmx-reporter]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.structured-logging.core :refer [maplog]]
            [puppetlabs.puppetdb.time :refer [to-millis periods-equal? parse-period period?]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote! with-trailing-slash]]
            [puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary :as bucketed-summary]
            [puppetlabs.pe-puppetdb-extensions.sync.events :as events]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [get-service service-context]]
            [puppetlabs.puppetdb.utils :as utils :refer [throw+-cli-error!]]
            [schema.core :as s]
            [clj-time.coerce :refer [to-date-time]]
            [slingshot.slingshot :refer [throw+ try+]]
            [com.rpl.specter :as sp]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.http.client.sync :as http-sync]
            [puppetlabs.pe-puppetdb-extensions.sync.status :as sync-status]
            [puppetlabs.puppetdb.threadpool :as tp]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]))

(defprotocol PuppetDBSync
  (bucketed-summary-query [this entity])
  (sync-status [this])
  (pull-records-from [this remote-server])
  (blocking-pull-records-from [this remote-server make-threadpool-fn]))

(def currently-syncing (atom false))

(def sync-request-schema {:remote_host_path s/Str})

(defn remote-url->server-url
  "Given a 'remote-url' of form <path>/pdb/query/v4, return <path>. Currently
   used for logging and tests."
  [remote-url]
  (let [uri (URI. remote-url)
        prefix (str (.getScheme uri) "://" (.getHost uri) ":" (.getPort uri))
        suffix (re-find #"^\/[a-zA-Z0-9-_\/]*(?=/pdb/query/v4)" (.getPath uri))]
    (str prefix suffix)))

(defn- query-endpoint
  "Given a base server-url, put the query endpoint onto the end of it. Don't do
  this if it looks like one is already there. "
  [^String server-url]
  (if (.contains (str server-url) "/v4")
    server-url
    (str (with-trailing-slash (str server-url)) "pdb/query/v4")))

(defrecord RemoteServer [url client]
  java.io.Closeable
  (close [this] (.close client)))

(defn make-remote-server
  "Create a remote-server structure out the given url, pulling ssl options from
   jetty-config. You should call .close on this when you're done with it (or use
   it inside 'with-open') to clean up any persistent network connections. "
  [remote-config jetty-config]
  (let [server-url (str (:server-url remote-config))]
    (map->RemoteServer {:url (query-endpoint server-url)
                        :client (http-sync/create-client
                                  (select-keys jetty-config [:ssl-cert :ssl-key :ssl-ca-cert]))})))

(defn- sync-with!
  [remote-server
   query-fn
   bucketed-summary-query-fn
   enqueue-command-fn
   node-ttl
   sync-status-atom]
  (if (compare-and-set! currently-syncing false true)
    (try
      (swap! sync-status-atom sync-status/reset :syncing)
      (sync-from-remote! query-fn bucketed-summary-query-fn
                         enqueue-command-fn remote-server node-ttl
                         #(swap! sync-status-atom sync-status/update-for-status-message %))
      (swap! sync-status-atom sync-status/reset :idle)
      {:status 200 :body "success"}
      (catch Exception ex
        (let [url (:url remote-server)
              err (format "Sync from %s failed" url)]
          (maplog [:sync :error] ex
                  {:remote url :phase "sync"}
                  err)
          (log/errorf ex err url)
          (swap! sync-status-atom sync-status/update-for-error err)
          {:status 200 :body err}))
      (finally
        (reset! currently-syncing false)))
    (let [err "Refusing to sync from %s. Sync already in progress."
          url (:url remote-server)]
      (maplog [:sync :info]
              {:remote url :phase "sync"}
              (format err url))
      (log/infof err url)
      {:status 200 :body (format err url)})))

(defn enable-periodic-sync? [remotes]
  (cond
    (or (nil? remotes) (zero? (count remotes)))
    (do
      (log/warn "No remotes specified, sync disabled")
      false)

    :else
    (let [interval (-> remotes first (get :interval ::none))]
      (cond
        (= interval ::none)
        false

        (not (period? interval))
        (throw+-cli-error!
          (format "Specified sync interval %s does not appear to be a time period" interval))

        (neg? (time/in-millis interval))
        (throw+-cli-error! (str "Sync interval must be positive or zero: " interval))

        (periods-equal? interval Period/ZERO)
        (do (log/warn "Zero sync interval specified, disabling sync.")
            false)

        :else true))))

(defn validate-trigger-sync
  "Validates `remote-server' as a valid sync target given user config items"
  [allow-unsafe-sync-triggers remotes-config jetty-config remote-server]
  (let [valid-remote-urls (for [remote-config remotes-config]
                            (with-open [remote-server (make-remote-server remote-config jetty-config)]
                              (:url remote-server)))]
    (or allow-unsafe-sync-triggers
        (ks/seq-contains? valid-remote-urls (:url remote-server)))))

(defn blocking-sync
  [remote-server query-fn bucketed-summary-query-fn process-command-fn
   node-ttl make-threadpool-fn sync-status-atom]
  (with-open [threadpool (make-threadpool-fn)]
    (let [remote-url (:url remote-server)
          command-chan (async/chan)
          enqueue-command (fn enqueue-command-for-initial-sync [command-kw version payload]
                            (async/>!! command-chan
                                       {:command (command-names command-kw)
                                        :version version
                                        :annotations {:id (ks/uuid) :received (time/now)}
                                        :payload payload}))
          process-command (fn process-command-for-initial-sync [command]
                            (process-command-fn command)
                            (swap! sync-status-atom sync-status/update-for-command (:command command)))
          all-executions-scheduled-chan (async/go (tp/dochan threadpool process-command command-chan))]
      (try
        (swap! sync-status-atom sync-status/reset :syncing)
        (sync-from-remote! query-fn bucketed-summary-query-fn enqueue-command remote-server node-ttl
                           #(swap! sync-status-atom sync-status/update-for-status-message %))
        (finally
          (async/close! command-chan)))
      (async/alt!!
        (async/timeout 15000)
        (do
          (swap! sync-status-atom
                 sync-status/update-for-error "Timed out due to slow processing")
          (throw+ {:type ::message-processing-timeout}
                  (str "The blocking sync with the remote system timed out "
                       "because of slow message processing.")))

        all-executions-scheduled-chan
        nil)))
  ;; The threadpool closes when exiting the precending with-open form, which
  ;; will block until all pending commands have finished processing
  (swap! sync-status-atom sync-status/reset :idle))

(defn sync-handler
  "Top level route for PuppetDB sync"
  [sync-service get-config]
  (let [{{node-ttl :node-ttl} :database
         sync-config :sync
         jetty-config :jetty
         {threadpool-size :threads} :command-processing} (get-config)
        allow-unsafe-sync-triggers (:allow-unsafe-sync-triggers sync-config)
        remotes-config (:remotes sync-config)
        validate-sync-fn (partial validate-trigger-sync
                                  allow-unsafe-sync-triggers
                                  remotes-config
                                  jetty-config)]
    (mid/make-pdb-handler
     (cmdi/context "/v1"
                   (cmdi/GET "/reports-summary" []
                             (let [summary-map (bucketed-summary-query sync-service :reports)]
                               (http/json-response (ks/mapkeys str summary-map))))

                   (cmdi/GET "/catalogs-summary" []
                     (let [summary-map (bucketed-summary-query sync-service :catalogs)]
                       (http/json-response (ks/mapkeys str summary-map))))

                   (cmdi/POST "/trigger-sync" {:keys [body params] :as request}
                              (let [sync-request (json/parse-string (slurp body) true)
                                    remote-url (:remote_host_path sync-request)
                                    completion-timeout-ms (some-> params
                                                                  (get "secondsToWaitForCompletion")
                                                                  Double/parseDouble
                                                                  (* 1000))]
                                (s/validate sync-request-schema sync-request)
                                (with-open [remote-server (make-remote-server {:server-url remote-url} jetty-config)]
                                  (cond
                                    (not (validate-sync-fn remote-server))
                                    {:status 503 :body (format "Refusing to sync. PuppetDB is not configured to sync with %s" remote-url)}

                                    completion-timeout-ms
                                    (do
                                      (maplog [:sync :info] {:remote (:url remote-server)}
                                              "Performing blocking sync with timeout of %s ms" completion-timeout-ms)
                                      (async/alt!!
                                        ;; This should be changed to use the regular command processing
                                        ;; thread pool once it's changed over.
                                        (async/go
                                          (blocking-pull-records-from sync-service remote-server
                                                                      #(tp/create-threadpool threadpool-size "blocking-sync-%d" 5000)))
                                        (http/json-response {:timed_out false} 200)

                                        (async/timeout completion-timeout-ms)
                                        (http/json-response {:timed_out true} 503)))

                                    :else
                                    (pull-records-from sync-service remote-server)))))))))

(defn start-bucketed-summary-cache-invalidation-job [response-mult cache-atom]
  (let [processed-commands-chan (async/chan)]
    (async/tap response-mult processed-commands-chan)
    (async/go-loop []
      (when-let [processed-command (async/<!! processed-commands-chan)]
        (bucketed-summary/invalidate-cache-for-command cache-atom processed-command)
        (recur)))
    #(do (async/untap response-mult processed-commands-chan)
         (async/close! processed-commands-chan))))

(defn process-or-enqueue-command
  "Try to immediately process the command; if that fails, queue it for later processing."
  [cmd scf-write-db enqueue-fn]
  (try
   (command/call-with-quick-retry
    4
    #(command/process-command! cmd scf-write-db))
   (catch Exception e
     (enqueue-fn cmd))))

(defn attempt-initial-sync
  "Attempt an initial sync against a remote-server. On success, return :success,
   on exception other than message-processing-timeout, log warning and return
   nil."
  [remote-config jetty-config query-fn shared-globals cache-atom
   response-mult node-ttl enqueue-command-fn sync-status-atom
   num-processing-threads]
  (with-open [remote-server (make-remote-server remote-config jetty-config)]
    (let [server-url (remote-url->server-url (:url remote-server))
          {:keys [scf-read-db scf-write-db]} shared-globals]
      (try+
       (maplog [:sync :info] {:remote (str server-url)}
               "Performing initial blocking sync from {remote}...")
       (blocking-sync remote-server query-fn
                      (partial bucketed-summary/bucketed-summary-query scf-read-db cache-atom)
                      #(process-or-enqueue-command % scf-write-db enqueue-command-fn)
                      node-ttl
                      #(tp/create-threadpool num-processing-threads "initial-sync-%d" 5000)
                      sync-status-atom)
       :success
       (catch [:type ::message-processing-timeout] _
         ;; Something is very wrong if we hit this timeout; rethrow the
         ;; exception to crash the server.
         (throw+))
       ;; any other exception is basically ok; just log a warning and
       ;; keep on going.
       (catch [] ex
         (log/warn ex (format "Could not perform initial sync with %s" server-url)))
       (catch Exception ex
         (log/warn ex (format "Could not perform initial sync with %s" server-url)))))))

(defservice puppetdb-sync-service
  PuppetDBSync
  [[:DefaultedConfig get-config]
   [:PuppetDBServer query shared-globals]
   [:PuppetDBCommandDispatcher enqueue-command response-mult]]

  (init [this context]
        (jmx-reporter/start (:reporter events/sync-metrics))
        (assoc context
               :sync-status-atom (atom sync-status/initial)))

  (start [this context]
         (let [{{node-ttl :node-ttl} :database
                sync-config :sync
                jetty-config :jetty
                {threadpool-size :threads} :command-processing} (get-config)
               remotes-config (:remotes sync-config)
               response-mult (response-mult)
               cache-atom (atom {})
               context (assoc context
                              :bucketed-summary-cache-atom
                              cache-atom
                              :cache-invalidation-job-stop-fn
                              (start-bucketed-summary-cache-invalidation-job response-mult
                                                                             cache-atom))]
           (jdbc/with-db-connection (:scf-read-db (shared-globals))
             (when-not (sutils/pg-extension? "pgcrypto")
               (throw+-cli-error!
                (str "Missing PostgreSQL extension `pgcrypto`\n\n"
                     "Puppet Enterprise requires the pgcrypto extension to be installed. \n"
                     "Run the command:\n\n"
                     "    CREATE EXTENSION pgcrypto;\n\n"
                     "as the database super user on the PuppetDB database install it,\n"
                     "then restart PuppetDB.\n"))))

           (if (enable-periodic-sync? remotes-config)
             (let [_ (some #(attempt-initial-sync % jetty-config query (shared-globals)
                                                  cache-atom response-mult
                                                  node-ttl enqueue-command (:sync-status-atom context)
                                                  threadpool-size)
                           remotes-config)]
               (let [pool (atat/mk-pool)
                     nremotes (count remotes-config)]
                 (doseq [{:keys [interval] :as remote-config} remotes-config]
                   (let [interval-millis (to-millis interval)]
                     (atat/interspaced interval-millis
                                       #(with-open [remote-server (make-remote-server remote-config jetty-config)]
                                          (pull-records-from this remote-server))
                                       pool :initial-delay (rand-int interval-millis))))
                 (assoc context :job-pool pool)))
             context)))

  (stop [this context]
        (jmx-reporter/stop (:reporter events/sync-metrics))
        (when-let [pool (:job-pool context)]
          (log/info "Stopping pe-puppetdb sync with remotes")
          (atat/stop-and-reset-pool! pool))
        (when-let [stop-fn (:cache-invalidation-job-stop-fn context)]
          (stop-fn))
        (dissoc context :cache-invalidation-job-stop-fn))

  (bucketed-summary-query [this entity]
                          (bucketed-summary/bucketed-summary-query
                           (:scf-read-db (shared-globals))
                           (:bucketed-summary-cache-atom (service-context this))
                           entity))

  (sync-status [this]
               @(:sync-status-atom (service-context this)))

  (pull-records-from [this remote-server]
                     (let [{{node-ttl :node-ttl} :database} (get-config)
                           {:keys [sync-status-atom cache-atom]} (service-context this)]
                       (sync-with! remote-server query
                                   (partial bucketed-summary-query this)
                                   enqueue-command node-ttl sync-status-atom)))

  (blocking-pull-records-from [this remote-server make-threadpool-fn]
                              (let [{{node-ttl :node-ttl} :database} (get-config)
                                    {:keys [sync-status-atom cache-atom]} (service-context this)
                                    {:keys [scf-write-db]} (shared-globals)]
                                (blocking-sync remote-server query
                                               (partial bucketed-summary-query this)
                                               #(process-or-enqueue-command % scf-write-db enqueue-command)
                                               node-ttl
                                               make-threadpool-fn
                                               sync-status-atom))))
