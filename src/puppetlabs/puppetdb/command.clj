(ns puppetlabs.puppetdb.command
  "PuppetDB command handling

   Commands are the mechanism by which changes are made to PuppetDB's
   model of a population. Commands are represented by `command
   objects`, which have the following JSON wire format:

       {\"command\": \"...\",
        \"version\": 123,
        \"payload\": <json object>}

   `payload` must be a valid JSON string of any sort. It's up to an
   individual handler function how to interpret that object.

   More details can be found in [the spec](../spec/commands.md).

   Commands should include a `received` field containing a timestamp
   of when the message was first seen by the system. If this is
   omitted, it will be added when the message is first parsed, but may
   then be somewhat inaccurate.

   Commands should include an `id` field containing a unique integer
   identifier for the command. If this is omitted, it will be added
   when the message is first parsed.

   Failed messages will have an `attempts` annotation containing an
   array of maps of the form:

       {:timestamp <timestamp>
        :error     \"some error message\"
        :trace     <stack trace from :exception>}

   Each entry corresponds to a single failed attempt at handling the
   message, containing the error message, stack trace, and timestamp
   for each failure. PuppetDB may discard messages which have been
   attempted and failed too many times, or which have experienced
   fatal errors (including unparseable messages).

   Failed messages will be stored in files in the \"dead letter
   office\", located under the MQ data directory, in
   `/discarded/<command>`. These files contain the annotated message,
   along with each exception that occured while trying to handle the
   message.

   We currently support the following wire formats for commands:

   1. Java Strings

   2. UTF-8 encoded byte-array

   In either case, the command itself, once string-ified, must be a
   JSON-formatted string with the aforementioned structure."
  (:require [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.catalogs :as cat]
            [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.puppetdb.facts :as fact]
            [puppetlabs.puppetdb.nodes :as nodes]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.time :as fmt-time]
            [puppetlabs.puppetdb.time :as tcoerce]
            [puppetlabs.puppetdb.time :as time
             :refer [now in-millis interval to-timestamp]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.command.constants
             :refer [command-names command-keys supported-command-versions]]
            [puppetlabs.trapperkeeper.services
             :refer [defservice service-context]]
            [schema.core :as s]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.time :as time]
            [clojure.set :as set]
            [clojure.core.async :as async]
            [metrics.timers :refer [timer time!]]
            [metrics.counters :refer [inc! dec! counter]]
            [metrics.meters :refer [meter mark!]]
            [metrics.histograms :refer [histogram update!]]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [overtone.at-at :as at-at :refer [mk-pool stop-and-reset-pool!]]
            [puppetlabs.puppetdb.threadpool :as pool]
            [puppetlabs.puppetdb.utils.metrics :as mutils])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io Closeable)
   (java.util.concurrent Semaphore)
   (org.joda.time DateTime)))

;; ## Performance counters

;; For each command (and globally), add command-processing
;; metrics. The hierarchy of command-processing metrics has the
;; following form:
;;
;;     {"global"
;;        {:seen <meter>
;;         :processed <meter>
;;         :fatal <meter>
;;         :retried <meter>
;;         :awaiting-retry <counter>
;;         :discarded <meter>
;;         :processing-time <timer>
;;         :queue-time <histogram>
;;         :retry-counts <histogram>
;;         :size <histogram>
;;         :ignored <counter>
;;        }
;;      "command name"
;;        {<version number>
;;           {:seen <meter>
;;            :processed <meter>
;;            :fatal <meter>
;;            :retried <meter>
;;            :discarded <meter>
;;            :processing-time <timer>
;;            :retry-counts <histogram>
;;            :ignored <counter>
;;           }
;;        }
;;     }
;;
;; The `"global"` hierarchy contains metrics aggregated across all
;; commands.
;;
;; * `:seen`: the number of commands (valid or not) encountered
;; * `:processeed`: the number of commands successfully processed
;; * `:size`: the size of the message as reported by the submitting HTTP
;;            request's Content-Length header.
;; * `:fatal`: the number of fatal errors
;; * `:retried`: the number of commands re-queued for retry
;; * `:awaiting-retry`: the number of commands that are currently
;;                      waiting to be re-inserted into the queue
;; * `:discarded`: the number of commands discarded for exceeding the
;;                 maximum allowable retry count
;; * `:processing-time`: how long it takes to process a command
;; * `:queue-time`: how long the message spent in the queue before processing
;; * `:retry-counts`: histogram containing the number of times
;;                    messages have been retried prior to suceeding
;; * `:invalidated`: commands marked as delete?, caused by a newer command
;;                   was enqueued that will overwrite an existing one in the queue
;; * `:depth`: number of commands currently enqueued
;; * `:ignored`: number of obsolete commands that have been ignored.
;;

(def mq-metrics-registry (get-in metrics/metrics-registries [:mq :registry]))

(defn create-metrics [prefix]
  (let [to-metric-name-fn #(metrics/keyword->metric-name prefix %)]
    {:processing-time (timer mq-metrics-registry (to-metric-name-fn :processing-time))
     :message-persistence-time (timer mq-metrics-registry
                                      (to-metric-name-fn :message-persistence-time))
     :queue-time (histogram mq-metrics-registry (to-metric-name-fn :queue-time))
     :retry-counts (histogram mq-metrics-registry (to-metric-name-fn :retry-counts))
     :depth (counter mq-metrics-registry (to-metric-name-fn :depth))
     :invalidated (counter mq-metrics-registry (to-metric-name-fn :invalidated))
     :seen (meter mq-metrics-registry (to-metric-name-fn :seen))
     :size (histogram mq-metrics-registry (to-metric-name-fn :size))
     :processed (meter mq-metrics-registry (to-metric-name-fn :processed))
     :fatal (meter mq-metrics-registry (to-metric-name-fn :fatal))
     :retried (meter mq-metrics-registry (to-metric-name-fn :retried))
     :awaiting-retry (counter mq-metrics-registry (to-metric-name-fn :awaiting-retry))
     :discarded (meter mq-metrics-registry (to-metric-name-fn :discarded))
     :ignored (counter mq-metrics-registry (to-metric-name-fn :ignored))}))

(def metrics
  (atom {:command-parse-time (timer mq-metrics-registry
                                    (metrics/keyword->metric-name
                                     [:global] :command-parse-time))
         :message-persistence-time (timer mq-metrics-registry
                                          (metrics/keyword->metric-name
                                           [:global] :message-persistence-time))
         :global (create-metrics [:global])}))

(defn global-metric
  "Returns the metric identified by `name` in the `\"global\"` metric
  hierarchy"
  [name]
  {:pre [(keyword? name)]}
  (get-in @metrics [:global name]))

(defn cmd-metric
  [cmd version name]
  {:pre [(keyword? name)]}
  (get-in @metrics [(keyword (str cmd version)) name]))

(defn update-counter!
  [metric command version action!]
  (action! (global-metric metric))
  (action! (cmd-metric command version metric)))

(defn create-metrics-for-command!
  "Create a subtree of metrics for the given command and version (if
  present).  If a subtree of metrics already exists, this function is
  a no-op."
  [command version]
  (let [storage-path [(keyword (str command version))]]
    (when (= ::not-found (get-in @metrics storage-path ::not-found))
      (swap! metrics (fn [old-metrics]
                       ;; This check is to avoid a race of another
                       ;; thread adding in the storage path after the
                       ;; above check but before we put the new
                       ;; metrics in place
                       (if (= ::not-found (get-in old-metrics storage-path ::not-found))
                         (assoc-in old-metrics
                                   storage-path
                                   (create-metrics [(keyword command) (keyword (str version))]))
                         old-metrics))))))

(defn inc-cmd-depth
  "Ensures the `command` + `version` metric exists, then increments the
  depth for the given `command` and `version`"
  [command version]
  (create-metrics-for-command! command version)
  (update-counter! :depth command version inc!))

(defn mark-both-metrics!
  "Calls `mark!` on the global and command specific metric for `k`"
  [command version k]
  (mark! (global-metric k))
  (mark! (cmd-metric command version k)))

(defn fatality [cause]
  (ex-info "" {:kind ::fatal-processing-error} cause))

(defmacro upon-error-throw-fatality
  [& body]
  `(try
    ~@body
    (catch Exception e1#
      (throw (fatality e1#)))
    (catch AssertionError e2#
      (throw (fatality e2#)))))

;; ## Command submission

(defn-validated do-enqueue-command
  "Stores command in the q and returns its id."
  [q
   command-chan
   ^Semaphore write-semaphore
   {:keys [command certname command-stream compression] :as command-req} :- queue/command-req-schema
   maybe-send-cmd-event!]
  (try
    (.acquire write-semaphore)
    (time! (get @metrics :message-persistence-time)
           (let [cmdref (queue/store-command q command-req)
                 {:keys [id received]} cmdref]
             (async/>!! command-chan cmdref)
             (maybe-send-cmd-event! cmdref ::ingested)
             (log/debug (trs "[{0}-{1}] ''{2}'' command enqueued for {3}"
                             id
                             (tcoerce/to-long received)
                             command
                             certname))))
    (finally
      (.release write-semaphore)
      (when command-stream
        (.close ^Closeable command-stream)))))

;; FIXME: log db name, or lift out of per-db exec calls
(defn log-command-processed-messsage [id received-time start-time command-kw certname & [opts]]
  ;; manually stringify these to avoid locale-specific formatting
  (let [id (str id)
        received-time (str (tcoerce/to-long received-time))
        duration (str (in-millis (interval start-time (now))))
        command-name (command-names command-kw)
        puppet-version (:puppet-version opts)
        obsolete-cmd? (:obsolete-cmd? opts)]
    (cond
      puppet-version
      (log/info (trs "[{0}-{1}] [{2} ms] ''{3}'' puppet v{4} command processed for {5}"
                     id received-time duration command-name puppet-version certname))
      obsolete-cmd?
      (log/info (trs "[{0}-{1}] [{2} ms] ''{3}'' command ignored (obsolete) for {4}"
                     id received-time duration command-name certname))
      :else
      (log/info (trs "[{0}-{1}] [{2} ms] ''{3}'' command processed for {4}"
                     id received-time duration command-name certname)))))

;; Catalog replacement

(defn prep-replace-catalog [{:keys [payload received version] :as command}]
  (upon-error-throw-fatality
   (assoc command :payload (cat/parse-catalog payload version received))))

(defn exec-replace-catalog
  [{:keys [version id received payload]} start-time db]
  (let [{producer-timestamp :producer_timestamp certname :certname :as catalog} payload]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/replace-catalog! catalog received))
    (log-command-processed-messsage id received start-time :replace-catalog certname)))

;; Catalog input replacement

(defn prep-replace-catalog-inputs [command]
  (update-in command [:payload :producer_timestamp] #(or % (now))))

(defn exec-replace-catalog-inputs [{:keys [id received payload]} start-time db]
  (let [{:keys [certname inputs catalog_uuid]
         stamp :producer_timestamp} payload]
    (when (seq inputs)
      (jdbc/with-transacted-connection' db :repeatable-read
        (scf-storage/maybe-activate-node! certname stamp)
        (scf-storage/replace-catalog-inputs! certname catalog_uuid inputs stamp))
      (log-command-processed-messsage id received start-time
                                      :replace-catalog-inputs certname))))

;; Fact replacement

(defn rm-facts-by-regex [facts-blacklist fact-map]
  (let [blacklisted? (fn [fact-name]
                       (some #(re-matches % fact-name) facts-blacklist))]
    (apply dissoc fact-map (filter blacklisted? (keys fact-map)))))

(defn prep-replace-facts
  [{:keys [version received] :as command}
   {:keys [facts-blacklist facts-blacklist-type] :as blacklist-config}]
  (let [blacklisting? (seq blacklist-config)
        rm-blacklisted (when blacklisting?
                         (case facts-blacklist-type
                           "regex" (partial rm-facts-by-regex facts-blacklist)
                           "literal" #(apply dissoc % facts-blacklist)))]
    (update command :payload
            (fn [{:keys [package_inventory] :as prev}]
              (cond-> (upon-error-throw-fatality
                       (fact/normalize-facts version received prev))
                blacklisting? (update :values rm-blacklisted)
                (seq package_inventory) (update :package_inventory distinct))))))

(defn exec-replace-facts
  [{:keys [payload id received] :as command} start-time db]
  (let [{:keys [certname producer_timestamp]} payload]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer_timestamp)
      (scf-storage/replace-facts! payload))
    (log-command-processed-messsage id received start-time :replace-facts certname)))

;; Node deactivation

(defn deactivate-node-wire-v2->wire-3 [deactive-node]
  {:certname deactive-node})

(defn deactivate-node-wire-v1->wire-3 [deactive-node]
  (-> deactive-node
      (json/parse-string true)
      deactivate-node-wire-v2->wire-3))

(defn prep-deactivate-node [{:keys [version] :as command}]
  (-> command
      (update :payload #(upon-error-throw-fatality
                         (s/validate nodes/deactivate-node-wireformat-schema
                                     (case version
                                       1 (deactivate-node-wire-v1->wire-3 %)
                                       2 (deactivate-node-wire-v2->wire-3 %)
                                       %))))
      (update-in [:payload :producer_timestamp] #(or % (now)))))

;; FIXME: was to-timestamp redundant here and in store-report? others don't have it...

(defn exec-deactivate-node [{:keys [id received payload]} start-time db]
  (let [{:keys [certname producer_timestamp]} payload]
    (jdbc/with-transacted-connection db
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (scf-storage/deactivate-node! certname producer_timestamp))
    (log-command-processed-messsage id received start-time :deactivate-node certname)))

;; Report submission

(defn prep-store-report [{:keys [version received] :as command}]
  (-> command
      (update :payload #(upon-error-throw-fatality
                         (s/validate report/report-wireformat-schema
                                     (case version
                                       3 (report/wire-v3->wire-v8 % received)
                                       4 (report/wire-v4->wire-v8 % received)
                                       5 (report/wire-v5->wire-v8 %)
                                       6 (report/wire-v6->wire-v8 %)
                                       7 (report/wire-v7->wire-v8 %)
                                       %))))
      (update-in [:payload :producer_timestamp] #(or % (now)))))

(defn exec-store-report [{:keys [payload id received]} start-time db conn-status]
  (let [{:keys [certname puppet_version producer_timestamp] :as report} payload]
    ;; Unlike the other storage functions, add-report! manages its own
    ;; transaction, so that it can dynamically create table partitions
    (scf-storage/add-report! report received db conn-status)
    (log-command-processed-messsage id received start-time :store-report certname
                                    {:puppet-version puppet_version})))

;; Expiration configuration

(defn prep-configure-expiration [command]
  (upon-error-throw-fatality
   (-> command
       (update :payload #(s/validate nodes/configure-expiration-wireformat-schema %))
       (update-in [:payload :producer_timestamp] #(or % (now))))))

(defn exec-configure-expiration [{:keys [id received payload]} start-time db]
  (let [{:keys [certname producer_timestamp]} payload
        expire-facts? (get-in payload [:expire :facts])]
    (when-not (nil? expire-facts?)
      (jdbc/with-transacted-connection db
        (scf-storage/maybe-activate-node! certname producer_timestamp)
        (scf-storage/set-certname-facts-expiration certname expire-facts? producer_timestamp))
      (log-command-processed-messsage id received start-time
                                      :configure-expiration certname))))

;; ## Command processors

(defn prep-command [{:keys [command] :as cmd} blacklist-config]
  (case command
    "replace catalog" (prep-replace-catalog cmd)
    "replace facts" (prep-replace-facts cmd blacklist-config)
    "store report" (prep-store-report cmd)
    "deactivate node" (prep-deactivate-node cmd)
    "configure expiration" (prep-configure-expiration cmd)
    "replace catalog inputs" (prep-replace-catalog-inputs cmd)))

(def supported-command?
  (comp (kitchensink/valset command-names) :command))

(defn supported-version? [command version]
  (contains? (get supported-command-versions command #{}) version))

(defn supported-command-version? [command-name [received-command-name received-version]]
  (boolean
   (and (= command-name received-command-name)
        (supported-version? command-name received-version))))

(defn exec-command
  "Takes a command object and processes it to completion. Dispatch is
   based on the command's name and version information"
  [{command-name :command version :version delete? :delete? :as command}
   db conn-status start]
  (condp supported-command-version? [command-name version]
    "replace catalog" (exec-replace-catalog command start db)
    "replace facts" (exec-replace-facts command start db)
    "store report" (exec-store-report command start db conn-status)
    "deactivate node" (exec-deactivate-node command start db)
    "configure expiration" (exec-configure-expiration command start db)
    "replace catalog inputs" (exec-replace-catalog-inputs command start db)))

(defn process-command!
  ;; only used by testing...
  "Takes a command object and processes it to completion. Dispatch is
   based on the command's name and version information"
  [command db blacklist-config]
  (when-not (:delete? command)
    (let [start (now)]
      (-> command
          (prep-command blacklist-config)
          (exec-command db (atom {}) start)))))

(defn warn-deprecated
  "Logs a deprecation warning message for the given `command` and `version`"
  [version command]
  (log/warn (trs "command ''{0}'' version {1} is deprecated, use the latest version" command version)))

(defprotocol PuppetDBCommandDispatcher
  (enqueue-command
    [this command version certname producer-ts payload compression]
    [this command version certname producer-ts payload compression command-callback]
    "Submits the command for processing, and then returns its unique id.")

  (stats [this]
    "Returns command processing statistics as a map
    containing :received-commands (a count of the commands received so
    far by the current service instance), and :executed-commands (a
    count of the commands that the current instance has processed
    without triggering an exception).")

  (response-mult [this]
    "Returns a core.async mult to which {:id :exception} maps are written after
     each command has been processed. " ))

(defn make-cmd-processed-message [cmd ex]
  (conj
   ;; :delete? from cmdref is needed to check when a command gets "bashed"
   (conj (select-keys cmd [:id :command :version :delete?])
         [:producer-timestamp (get-in cmd [:payload :producer_timestamp])])
   (when ex
     [:exception ex])))

(defn call-with-quick-retry [num-retries f]
  (loop [n num-retries]
    (let [result (try
                  (f)
                  (catch Throwable e
                    (if (zero? n)
                      (throw e)
                      (do (log/debug e
                                     (trs "Exception throw in L1 retry attempt {0}"
                                          (- (inc num-retries) n)))
                          ::failure))))]
      (if (= result ::failure)
        (recur (dec n))
        result))))

(def quick-retry-count 4)

(defn attempt-exec-command
  [{:keys [callback] :as cmd} db conn-status response-pub-chan stats-atom]
  (try
    (let [result (call-with-quick-retry quick-retry-count
                                        #(exec-command cmd db conn-status (now)))]
      (swap! stats-atom update :executed-commands inc)
      (callback {:command cmd :result result})
      (async/>!! response-pub-chan
                 (make-cmd-processed-message cmd nil))
      result)
    (catch Exception ex
      (callback {:command cmd :exception ex})
      (async/>!! response-pub-chan
                 (make-cmd-processed-message cmd ex))
      (throw ex))))

;; The number of times a message can be retried before we discard it
(def maximum-allowable-retries 5)

(defn discard-message
  "Discards the given `cmdref` caused by `ex`"
  [cmdref ex q dlo maybe-send-cmd-event!]
  (-> cmdref
      (queue/cons-attempt ex)
      (dlo/discard-cmdref q dlo))
  (maybe-send-cmd-event! cmdref ::processed)
  (let [{:keys [command version]} cmdref]
    (mark-both-metrics! command version :discarded)
    (update-counter! :depth command version dec!)))

(defn process-delete-cmd
  "Processes a command ref marked for deletion. This is similar to
  processing a non-delete cmdref except different metrics need to be
  updated to indicate the difference in command"
  [cmd {:keys [command version certname id received] :as cmdref}
   q response-chan stats blacklist-config maybe-send-cmd-event!]
  (swap! stats update :executed-commands inc)
  ((:callback cmd) {:command cmd :result nil})
  (async/>!! response-chan (make-cmd-processed-message cmd nil))
  (log-command-processed-messsage id received (now) (command-keys command)
                                  certname {:obsolete-cmd? true})
  (queue/ack-command q {:entry (queue/cmdref->entry cmdref)})
  (maybe-send-cmd-event! cmdref ::processed)
  (update-counter! :depth command version dec!)
  (update-counter! :invalidated command version dec!))

(defn process-cmd
  "Processes and acknowledges a successful command ref and updates the
  relevant metrics. Any exceptions that arise are unhandled and
  expected to be caught by the caller."
  [cmd cmdref q scf-write-db response-chan stats maybe-send-cmd-event!]
  (let [{:keys [command version]} cmd
        retries (count (:attempts cmdref))]
    (create-metrics-for-command! command version)

    (mark-both-metrics! command version :seen)
    (update! (global-metric :retry-counts) retries)
    (update! (cmd-metric command version :retry-counts) retries)

    (mutils/multitime!
     [(global-metric :processing-time)
      (cmd-metric command version :processing-time)]

     (attempt-exec-command cmd scf-write-db response-chan stats)
     (mark-both-metrics! command version :processed))

    (queue/ack-command q cmd)
    (maybe-send-cmd-event! cmdref ::processed)
    (update-counter! :depth command version dec!)))

(def command-delay-ms (* 1000 60 60))

;; For testing via with-redefs
(defn enqueue-delayed-message [command-chan narrowed-entry]
  (async/>!! command-chan narrowed-entry))

;; For testing via with-redefs
(def schedule-msg-after at-at/after)

(defn schedule-delayed-message
  "Will delay `cmd` in the `delay-pool` threadpool for
  `command-delay-ms`. It will then be enqueued in `command-chan`
  for another attempt at processing"
  [cmd ex command-chan delay-pool stop-status]
  (let [narrowed-entry (-> cmd
                           (queue/cons-attempt ex)
                           (dissoc :payload))
        {:keys [command version]} cmd
        inc-msgs-if-not-stopping #(if (:stopping %)
                                    %
                                    (update % :executing-delayed inc))]
    (update-counter! :awaiting-retry command version inc!)
    (schedule-msg-after
     command-delay-ms
     (fn []
       (let [status (swap! stop-status inc-msgs-if-not-stopping)]
         (when-not (:stopping status)
           (try
             (enqueue-delayed-message command-chan narrowed-entry)
             (update-counter! :awaiting-retry command version dec!)
             (finally
               (swap! stop-status #(update % :executing-delayed dec)))))))
     delay-pool)))

(def ^:private iso-formatter (fmt-time/formatters :date-time))

(defn process-message
  [{:keys [certname command version received delete? id] :as cmdref}
   q command-chan dlo delay-pool broadcast-pool write-dbs response-chan stats
   blacklist-config stop-status maybe-send-cmd-event!]
  (when received
    (let [q-time (-> (fmt-time/parse iso-formatter received)
                     (time/interval (now))
                     time/in-seconds)]
      (create-metrics-for-command! command version)
      (update! (global-metric :queue-time) q-time)
      (update! (cmd-metric command version :queue-time) q-time)))
  (let [retries (count (:attempts cmdref))
        retry (fn [ex]
                (mark-both-metrics! command version :retried)
                (if (< retries maximum-allowable-retries)
                  (do
                    (log/error
                     ex
                     (trs "[{0}] [{1}] Retrying after attempt {2} for {3}, due to: {4} {5}"
                          id command retries certname ex (.getSuppressed ex)))
                    (schedule-delayed-message cmdref ex command-chan delay-pool stop-status))
                  (do
                    (log/error
                     ex
                     (trs "[{0}] [{1}] Exceeded max attempts ({2}) for {3} {4}"
                          id command retries certname (.getSuppressed ex)))
                    (discard-message cmdref ex q dlo maybe-send-cmd-event!))))]
    (try
      (let [cmd (queue/cmdref->cmd q cmdref)]
        (cond
          (not cmd) ;; queue file is missing
          (do
            (create-metrics-for-command! command version)
            (mark-both-metrics! command version :seen)
            (update-counter! :depth command version dec!)
            (mark! (global-metric :fatal))
            (maybe-send-cmd-event! cmdref ::processed))

          delete? (process-delete-cmd cmd cmdref q response-chan stats
                                      blacklist-config maybe-send-cmd-event!)

          :else (-> cmd
                    (prep-command blacklist-config)
                    (process-cmd cmdref q (first write-dbs) response-chan stats
                                 maybe-send-cmd-event!))))
      (catch ExceptionInfo ex
        (let [data (ex-data ex)]
          (case (:kind data)
            ::retry (retry ex)
            ::queue/parse-error
            (do
              (mark! (global-metric :fatal))
              (log/error ex (trs "Fatal error parsing command: {0}" (:id cmdref)))
              (discard-message cmdref ex q dlo maybe-send-cmd-event!))
            ::fatal-processing-error
            (do
              (mark! (global-metric :fatal))
              (log/error
               ex
               (trs "[{0}] [{1}] Fatal error on attempt {2} for {3}" id command retries certname))
              (discard-message cmdref (ex-cause ex) q dlo maybe-send-cmd-event!))
            ;; No match
            (retry ex))))
      (catch AssertionError ex
        (retry ex))
      (catch Exception ex
        (retry ex)))))

(defn message-handler
  "Processes the message via (process-message msg), retrying messages
  that fail via (delay-message msg), and discarding messages that have
  fatal errors or have exceeded their maximum allowed attempts."
  [q command-chan dlo delay-pool broadcast-pool write-dbs
   response-chan stats blacklist-config stop-status maybe-send-cmd-event!]
  (assert (= 1 (count write-dbs)))
  (fn [cmdref]
    (process-message cmdref
                     q command-chan dlo
                     delay-pool broadcast-pool write-dbs
                     response-chan stats
                     blacklist-config stop-status maybe-send-cmd-event!)))

(def stop-commands-wait-ms (constantly 5000))
(def threadpool-shutdown-ms 10000)

(defn create-command-handler-threadpool
  "Creates an unbounded threadpool with the intent that access to the
  threadpool is bounded by the semaphore. Implicitly the threadpool is
  bounded by `size`, but since the semaphore is handling that aspect,
  it's more efficient to use an unbounded pool and not duplicate the
  constraint in both the semaphore and the threadpool"
  [size]
  (pool/gated-threadpool size "cmd-proc-thread-%d" threadpool-shutdown-ms))

(defn create-broadcast-pool [cmd-concurrency write-dbs]
  (let [db-concurrency (* cmd-concurrency (count write-dbs))]
    (when (or (> db-concurrency cmd-concurrency)
              (->> (or (System/getenv "PDB_TEST_ALWAYS_BROADCAST_COMMANDS") "")
                   (re-matches #"yes|true|1")
                   seq))
      (pool/unbounded-threadpool "cmd-broadcast-thread-%d"
                                 threadpool-shutdown-ms))))

(defn start-command-service
  [context config {:keys [dlo] :as globals}]
  (if (get-in config [:global :upgrade-and-exit?])
    context
    (let [{:keys [command-chan scf-write-dbs q maybe-send-cmd-event!]} globals
          {:keys [response-chan response-pub]} context
          cmd-concurrency (conf/mq-thread-count config)
          command-pool (create-command-handler-threadpool cmd-concurrency)
          broadcast-pool (create-broadcast-pool cmd-concurrency scf-write-dbs)
          delay-pool (:delay-pool context)
          handle-cmd (message-handler q
                                      command-chan
                                      dlo
                                      delay-pool
                                      broadcast-pool
                                      scf-write-dbs
                                      response-chan
                                      (:stats context)
                                      (-> config
                                          :database
                                          (select-keys [:facts-blacklist
                                                        :facts-blacklist-type]))
                                      (:stop-status context)
                                      maybe-send-cmd-event!)]
      ;; The rejection below should only occur if new work is
      ;; submitted after the threadpool has started shutting down as
      ;; part of the service's shutdown.  Since the command will be
      ;; retried on the next restart, there's no reason to let this
      ;; bubble up and be reported.
      (let [shovel #(try
                      (pool/dochan command-pool handle-cmd command-chan)
                      (catch ExceptionInfo ex
                        (when-not (= :puppetlabs.puppetdb.threadpool/rejected
                                     (:kind (ex-data ex)))
                          (throw ex))))]
        (doto (Thread. shovel)
          (.setDaemon false)
          (.start)))
      (assoc context
             :command-chan command-chan
             :consumer-threadpool command-pool
             :broadcast-threadpool broadcast-pool))))

(defn stop-command-service
  [{:keys [stop-status consumer-threadpool broadcast-threadpool command-chan
           delay-pool]
    :as context}]
  (some-> command-chan async/close!)
  ;; FIXME: (PDB-4742) exception suppression?
  (some-> consumer-threadpool pool/shutdown-gated)
  (some-> broadcast-threadpool pool/shutdown-unbounded)
  (some-> delay-pool stop-and-reset-pool!)
  ;; Wait up to ~5s for https://bugs.openjdk.java.net/browse/JDK-8176254
  (swap! stop-status #(assoc % :stopping true))
  (if (utils/wait-for-ref-state stop-status (stop-commands-wait-ms)
                                #(= % {:stopping true :executing-delayed 0}))
    (log/info (trs "Halted delayed command processsing"))
    (log/info (trs "Forcibly terminating delayed command processing")))
  (async/unsub-all (:response-pub context))
  (async/untap-all (:response-mult context))
  (async/close! (:response-chan-for-pub context))
  (async/close! (:response-chan context))
  (dissoc context
          :response-pub :response-chan :response-chan-for-pub :response-mult))

(defservice command-service
  PuppetDBCommandDispatcher
  [[:DefaultedConfig get-config]
   [:PuppetDBServer shared-globals]]
  (init [this context]
    (let [response-chan (async/chan 1000)
          response-mult (async/mult response-chan)
          response-chan-for-pub (async/chan)
          concurrent-writes (get-in (get-config) [:command-processing :concurrent-writes])]
      (async/tap response-mult response-chan-for-pub)
      (assoc context
             :delay-pool (mk-pool)
             :write-semaphore (Semaphore. concurrent-writes)
             :stats (atom {:received-commands 0
                           :executed-commands 0})
             :response-chan response-chan
             :response-mult response-mult
             :response-chan-for-pub response-chan-for-pub
             :response-pub (async/pub response-chan-for-pub :id)
             ;; This coordination is needed until we no longer
             ;; support jdk < 10.
             ;; https://bugs.openjdk.java.net/browse/JDK-8176254
             :stop-status (atom {:executing-delayed 0}))))

  (start [this context] (start-command-service context (get-config) (shared-globals)))
  (stop [this context] (stop-command-service context))

  (stats [this]
    @(:stats (service-context this)))

  (enqueue-command [this command version certname producer-ts command-stream compression]
                   (enqueue-command this command version certname producer-ts command-stream compression identity))

  (enqueue-command [this command version certname producer-ts command-stream compression command-callback]
   (let [config (get-config)
         globals (shared-globals)
         q (:q globals)
         command-chan (:command-chan globals)
         write-semaphore (:write-semaphore (service-context this))
         command (if (string? command) command (command-names command))
         command-req (queue/create-command-req command version certname producer-ts compression command-callback command-stream)
         result (do-enqueue-command q command-chan write-semaphore command-req (:maybe-send-cmd-event! globals))]
      ;; Obviously assumes that if do-* doesn't throw, msg is in
      (inc-cmd-depth command version)
      (swap! (:stats (service-context this)) update :received-commands inc)
      result))

  (response-mult [this]
    (-> this service-context :response-mult)))
