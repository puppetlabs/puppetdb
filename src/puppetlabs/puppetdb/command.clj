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
  (:import [java.util.concurrent Semaphore])
  (:require [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.catalogs :as cat]
            [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.puppetdb.facts :as fact]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.puppetdb.mq-listener :as mql]
            [puppetlabs.puppetdb.command.constants
             :refer [command-names supported-command-versions]]
            [puppetlabs.trapperkeeper.services
             :refer [defservice service-context]]
            [schema.core :as s]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clj-time.core :refer [now]]
            [clojure.set :as set]
            [clojure.core.async :as async]
            [metrics.timers :refer [timer time!]]
            [metrics.counters :refer [inc!]]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [puppetlabs.puppetdb.queue :as queue]
            [clj-time.coerce :as tcoerce]
            [puppetlabs.puppetdb.amq-migration :as mig]
            [puppetlabs.puppetdb.command.dlo :as dlo]))

(def mq-metrics-registry (get-in metrics/metrics-registries [:mq :registry]))

(def metrics (atom {:command-parse-time (timer mq-metrics-registry
                                               (metrics/keyword->metric-name
                                                 [:global] :command-parse-time))
                    :message-persistence-time (timer mq-metrics-registry
                                                     (metrics/keyword->metric-name
                                                       [:global] :message-persistence-time))}))

(defn fatality
  "Create an object representing a fatal command-processing exception

  cause - object representing the cause of the failure
  "
  [cause]
  {:fatal true :cause cause})

(defmacro upon-error-throw-fatality
  [& body]
  `(try+
    ~@body
    (catch Throwable e#
      (throw+ (fatality e#)))))

;; ## Command submission

(defn-validated do-enqueue-command
  "Submits command to the mq-endpoint of mq-connection and returns
  its id."
  [q
   command-chan
   ^Semaphore write-semaphore
   command :- s/Str
   version :- s/Int
   certname :- s/Str
   command-stream
   command-callback]
  (try
    (.acquire write-semaphore)
    (time! (get @metrics :message-persistence-time)
           (async/>!! command-chan
                      (queue/store-command
                        q command version certname command-stream command-callback)))
    (finally
      (.release write-semaphore)
      (when command-stream
        (.close command-stream)))))

;; Catalog replacement

(defn replace-catalog*
  [{:keys [certname version id received payload]} db]
  (let [{producer-timestamp :producer_timestamp :as catalog} payload]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/replace-catalog! catalog received))
    (log/infof "[%s] [%s] %s" id (command-names :replace-catalog) certname)))

(defn replace-catalog [{:keys [payload received version] :as command} db]
  (let [validated-payload (upon-error-throw-fatality
                           (cat/parse-catalog payload version received))]
    (-> command
        (assoc :payload validated-payload)
        (replace-catalog* db))))

;; Fact replacement

(defn replace-facts*
  [{:keys [payload id] :as command} db]
  (let [{:keys [certname values] :as fact-data} payload
        producer-timestamp (:producer_timestamp fact-data)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/replace-facts! fact-data))
    (log/infof "[%s] [%s] %s" id (command-names :replace-facts) certname)))

(defn replace-facts [{:keys [payload version received] :as command} db]
  (let [validated-payload (upon-error-throw-fatality
                           (-> (case version
                                 2 (fact/wire-v2->wire-v5 payload received)
                                 3 (fact/wire-v3->wire-v5 payload)
                                 4 (fact/wire-v4->wire-v5 payload)
                                 payload)
                               (update :values utils/stringify-keys)
                               (update :producer_timestamp to-timestamp)
                               (assoc :timestamp received)))]
    (-> command
        (assoc :payload validated-payload)
        (replace-facts* db))))

;; Node deactivation

(defn deactivate-node-wire-v2->wire-3 [deactive-node]
  {:certname deactive-node})

(defn deactivate-node-wire-v1->wire-3 [deactive-node]
  (-> deactive-node
      (json/parse-string true)
      upon-error-throw-fatality
      deactivate-node-wire-v2->wire-3))

(defn deactivate-node*
  [{:keys [id payload]} db]
  (let [certname (:certname payload)
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))]
    (jdbc/with-transacted-connection db
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (scf-storage/deactivate-node! certname producer-timestamp))
    (log/infof "[%s] [%s] %s" id (command-names :deactivate-node) certname)))

(defn deactivate-node [{:keys [payload version] :as command} db]
  (-> command
      (assoc :payload (case version
                        1 (deactivate-node-wire-v1->wire-3 payload)
                        2 (deactivate-node-wire-v2->wire-3 payload)
                        payload))
      (deactivate-node* db)))

;; Report submission

(defn store-report*
  [{:keys [payload id received]} db]
  (let [{:keys [certname puppet_version] :as report} payload
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))]
    (jdbc/with-transacted-connection db
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/add-report! report received))
    (log/infof "[%s] [%s] puppet v%s - %s"
               id
               (command-names :store-report)
               puppet_version
               certname)))

(defn store-report [{:keys [payload version received] :as command} db]
  (let [validated-payload (upon-error-throw-fatality
                           (s/validate report/report-wireformat-schema
                                       (case version
                                         3 (report/wire-v3->wire-v8 payload received)
                                         4 (report/wire-v4->wire-v8 payload received)
                                         5 (report/wire-v5->wire-v8 payload)
                                         6 (report/wire-v6->wire-v8 payload)
                                         7 (report/wire-v7->wire-v8 payload)
                                         payload)))]
    (-> command
        (assoc :payload validated-payload)
        (store-report* db))))

;; ## Command processors

(def supported-command?
  (comp (kitchensink/valset command-names) :command))

(defn supported-version? [command version]
  (contains? (get supported-command-versions command #{}) version))

(defn supported-command-version? [command-name [received-command-name received-version]]
  (boolean
   (and (= command-name received-command-name)
        (supported-version? command-name received-version))))

(defn process-command!
  "Takes a command object and processes it to completion. Dispatch is
  based on the command's name and version information"
  [{command-name :command version :version delete? :delete? :as command} db]
  (when-not delete?
    (condp supported-command-version? [command-name version]
      "replace catalog" (replace-catalog command db)
      "replace facts" (replace-facts command db)
      "store report" (store-report command db)
      "deactivate node" (deactivate-node command db))))

(defn warn-deprecated
  "Logs a deprecation warning message for the given `command` and `version`"
  [version command]
  (log/warnf "command '%s' version %s is deprecated, use the latest version" command version))

(defprotocol PuppetDBCommandDispatcher
  (enqueue-command
    [this command version certname payload]
    [this command version certname payload command-callback]
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
   (conj (select-keys cmd [:id :command :version])
         [:producer-timestamp (get-in cmd [:payload :producer_timestamp])])
   (when ex
     [:exception ex])))

(defn call-with-quick-retry [num-retries f]
  (loop [n num-retries]
    (let [result (try+
                  (f)
                  (catch Throwable e
                    (if (zero? n)
                      (throw e)
                      (do (log/debug e (i18n/trs "Exception throw in L1 retry attempt {0}" (- (inc num-retries) n)))
                          ::failure))))]
      (if (= result ::failure)
        (recur (dec n))
        result))))

(defn process-command-and-respond! [{:keys [callback] :as cmd} db response-pub-chan stats-atom]
  (try
    (let [result (call-with-quick-retry 4
                  (fn []
                    (process-command! cmd db)))]
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

(defn upgrade-activemq [config enqueue-fn dlo]
  (when (mig/needs-upgrade? config)
    (mig/activemq->stockpile config enqueue-fn dlo)
    (mig/lock-upgrade config)))

(defservice command-service
  PuppetDBCommandDispatcher
  [[:DefaultedConfig get-config]
   [:PuppetDBServer shared-globals]
   [:MessageListenerService register-listener]]
  (init [this context]
    (let [response-chan (async/chan 1000)
          response-mult (async/mult response-chan)
          response-chan-for-pub (async/chan)
          concurrent-writes (get-in (get-config) [:command-processing :concurrent-writes])]
      (async/tap response-mult response-chan-for-pub)
      (assoc context
             :write-semaphore (Semaphore. concurrent-writes)
             :stats (atom {:received-commands 0
                           :executed-commands 0})
             :response-chan response-chan
             :response-mult response-mult
             :response-chan-for-pub response-chan-for-pub
             :response-pub (async/pub response-chan-for-pub :id))))

  (start [this context]
    (let [{:keys [scf-write-db dlo q]} (shared-globals)
          {:keys [response-chan response-pub]} context]
      (register-listener
       supported-command?
       #(process-command-and-respond! % scf-write-db response-chan (:stats context)))

      (upgrade-activemq (get-config)
                        (partial enqueue-command this)
                        dlo)

      context))

  (stop [this context]
    (async/unsub-all (:response-pub context))
    (async/untap-all (:response-mult context))
    (async/close! (:response-chan-for-pub context))
    (async/close! (:response-chan context))
    (dissoc context :response-pub :response-chan :response-chan-for-pub :response-mult)
    context)

  (stats [this]
    @(:stats (service-context this)))

  (enqueue-command [this command version certname command-stream]
                   (enqueue-command this command version certname command-stream identity))

  (enqueue-command [this command version certname command-stream command-callback]
    (let [config (get-config)
          q (:q (shared-globals))
          command-chan (:command-chan (shared-globals))
          write-semaphore (:write-semaphore (service-context this))
          command (if (string? command) command (command-names command))
          result (do-enqueue-command q command-chan write-semaphore command
                                     version certname command-stream command-callback)]
      ;; Obviously assumes that if do-* doesn't throw, msg is in
      (mql/inc-cmd-metrics command version)
      (swap! (:stats (service-context this)) update :received-commands inc)
      result))

  (response-mult [this]
    (-> this service-context :response-mult)))
