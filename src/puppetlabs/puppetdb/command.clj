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

   The command object may also contain an `annotations` attribute
   containing a map with arbitrary keys and values which may have
   command-specific meaning or may be used by the message processing
   framework itself.

   Commands should include a `received` annotation containing a
   timestamp of when the message was first seen by the system. If this
   is omitted, it will be added when the message is first parsed, but
   may then be somewhat inaccurate.

   Commands should include an `id` annotation containing a unique,
   string identifier for the command. If this is omitted, it will be
   added when the message is first parsed.

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
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.catalogs :as cat]
            [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.puppetdb.facts :as fact]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.services
             :refer [defservice service-context]]
            [schema.core :as s]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clj-time.core :refer [now]]
            [clojure.set :as set]
            [clojure.core.async :as async]))

(defn version-range [min-version max-version]
  (set (range min-version (inc max-version))))

(def supported-command-versions
  {"replace facts" (version-range 2 4)
   "replace catalog" (version-range 4 7)
   "store report" (version-range 3 6)
   "deactivate node" (version-range 1 3)})

;; ## Command parsing

(defn parse-command
  "Take a wire-format command and parse it into a command object."
  [{:keys [headers body]}]
  {:post [(map? %)
          (:payload %)
          (string? (:command %))
          (number? (:version %))
          (map? (:annotations %))]}
  (let [message (if (= (class body) (class (byte-array 0)))
                      (json/parse-string (String. body "UTF-8") true)
                      (json/parse-string body true))
        default-annotations {:received (kitchensink/timestamp)
                             :id (kitchensink/uuid)}]
    (update message :annotations
            merge default-annotations headers)))

;; ## Command submission

(defn-validated ^:private do-enqueue-raw-command :- s/Str
  "Submits raw-command to the mq-endpoint of mq-connection and returns
  its id."
  [mq-connection :- mq/connection-schema
   mq-endpoint :- s/Str
   raw-command :- s/Str
   uuid :- (s/maybe s/Str)]
  (let [uuid (or uuid (kitchensink/uuid))]
    (mq/send-message! mq-connection mq-endpoint
                      raw-command
                      {"received" (kitchensink/timestamp) "id" uuid})
    uuid))

(defn-validated ^:private do-enqueue-command :- s/Str
  "Submits command to the mq-endpoint of mq-connection and returns
  its id. Annotates the command via annotate-command."
  [mq-connection :- mq/connection-schema
   mq-endpoint :- s/Str
   command :- s/Str
   version :- s/Int
   payload
   uuid :- (s/maybe s/Str)]
  (let [command-map {:command command
                     :version version
                     :payload payload}]
    (do-enqueue-raw-command mq-connection mq-endpoint (json/generate-string command-map) uuid)))

;; ## Command processing exception classes

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

;; Catalog replacement

(defn replace-catalog*
  [{:keys [payload annotations version]} db]
  (let [{id :id received-timestamp :received} annotations
        {producer-timestamp :producer_timestamp certname :certname :as catalog} payload]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/store-catalog! catalog received-timestamp))
    (log/infof "[%s] [%s] %s" id (command-names :replace-catalog) certname)))

(defn replace-catalog [{:keys [payload annotations version] :as command} db]
  (let [{received-timestamp :received} annotations
        validated-payload (upon-error-throw-fatality
                           (cat/parse-catalog payload version received-timestamp))]
    (-> command
        (assoc :payload validated-payload)
        (replace-catalog* db))))

;; Fact replacement

(defn replace-facts*
  [{:keys [payload annotations version]} db]
  (let [{:keys [id]} annotations
        {:keys [certname values] :as fact-data} payload
        producer-timestamp (:producer_timestamp fact-data)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/replace-facts! fact-data))
    (log/infof "[%s] [%s] %s" id (command-names :replace-facts) certname)))

(defn replace-facts [{:keys [payload version annotations] :as command} db]
  (let [{received-timestamp :received} annotations
        latest-version-of-payload (case version
                                    2 (fact/wire-v2->wire-v4 payload received-timestamp)
                                    3 (fact/wire-v3->wire-v4 payload)
                                    payload)
        validated-payload (upon-error-throw-fatality
                           (-> latest-version-of-payload
                               (update :values utils/stringify-keys)
                               (update :producer_timestamp to-timestamp)
                               (assoc :timestamp received-timestamp)))]
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
  [{:keys [payload annotations]} db]
  (let [certname (:certname payload)
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))
        id (:id annotations)]
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
  [{:keys [payload annotations]} db]
  (let [{id :id received-timestamp :received} annotations
        {:keys [certname puppet_version] :as report} payload
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))]
    (jdbc/with-transacted-connection db
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/add-report! report received-timestamp))
    (log/infof "[%s] [%s] puppet v%s - %s"
               id (command-names :store-report)
               puppet_version certname)))

(defn store-report [{:keys [payload version annotations] :as command} db]
  (let [{received-timestamp :received} annotations
        latest-version-of-payload (case version
                                    3 (report/wire-v3->wire-v6 payload received-timestamp)
                                    4 (report/wire-v4->wire-v6 payload received-timestamp)
                                    5 (report/wire-v5->wire-v6 payload)
                                    payload)
        validated-payload (upon-error-throw-fatality
                           (s/validate report/report-wireformat-schema latest-version-of-payload))]
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
  [{command-name :command version :version :as command} db]
  (condp supported-command-version? [command-name version]
    "replace catalog" (replace-catalog command db)
    "replace facts" (replace-facts command db)
    "store report" (store-report command db)
    "deactivate node" (deactivate-node command db)))

(defn warn-deprecated
  "Logs a deprecation warning message for the given `command` and `version`"
  [version command]
  (log/warnf "command '%s' version %s is deprecated, use the latest version" command version))

(defprotocol PuppetDBCommandDispatcher
  (enqueue-command
    [this command version payload]
    [this command version payload uuid]
    "Annotates the command via annotate-command, submits it for
    processing, and then returns its unique id.")

  (enqueue-raw-command [this raw-command uuid]
    "Submits the raw-command for processing and returns the command's
    unique id.")

  (stats [this]
    "Returns command processing statistics as a map
    containing :received-commands (a count of the commands received so
    far by the current service instance), and :executed-commands (a
    count of the commands that the current instance has processed
    without triggering an exception).")

  (response-mult [this]
    "Returns a core.async mult to which {:id :exception} maps are written after
     each command has been processed. " )

  (response-pub [this]
    "Returns a core.async pub to which {:id :exception} maps are written after
     each command has been processed. The topic for each message is its
     id (command uuid)."))

(defn make-cmd-processed-message [cmd ex]
  (merge {:id (-> cmd :annotations :id)
          :command (:command cmd)
          :version (:version cmd)
          :producer-timestamp (-> cmd :payload :producer_timestamp)}
         (when ex {:exception ex})))

(defn process-command-and-respond! [cmd db response-pub-chan stats-atom]
  (try
    (let [result (process-command! cmd db)]
      (swap! stats-atom update :executed-commands inc)
      (async/>!! response-pub-chan
                 (make-cmd-processed-message cmd nil))
      result)
    (catch Exception ex
      (async/>!! response-pub-chan
                 (make-cmd-processed-message cmd ex))
      (throw ex))))

(defservice command-service
  PuppetDBCommandDispatcher
  [[:DefaultedConfig get-config]
   [:PuppetDBServer shared-globals]
   [:MessageListenerService register-listener]]
  (init [this context]
    (let [response-chan (async/chan 1000)
          response-mult (async/mult response-chan)
          response-chan-for-pub (async/chan)]
      (async/tap response-mult response-chan-for-pub)
      (assoc context
             :stats (atom {:received-commands 0
                           :executed-commands 0})
             :response-chan response-chan
             :response-mult response-mult
             :response-chan-for-pub response-chan-for-pub
             :response-pub (async/pub response-chan-for-pub :id))))

  (start [this context]
    (let [{:keys [scf-write-db]} (shared-globals)
          {:keys [response-chan response-pub]} context
          factory (-> (conf/mq-broker-url (get-config))
                      (mq/activemq-connection-factory))
          connection (.createConnection factory)]
      (register-listener
       supported-command?
       #(process-command-and-respond! % scf-write-db response-chan (:stats context)))
      (assoc context
             :factory factory
             :connection connection)))

  (stop [this context]
    (async/unsub-all (:response-pub context))
    (async/untap-all (:response-mult context))
    (async/close! (:response-chan-for-pub context))
    (async/close! (:response-chan context))
    (dissoc context :response-pub :response-chan :response-chan-for-pub :response-mult)
    (.close (:connection context))
    (.close (:factory context))
    context)

  (stats [this]
    @(:stats (service-context this)))

  (enqueue-command [this command version payload]
    (enqueue-command this command version payload nil))

  (enqueue-command [this command version payload uuid]
    (let [config (get-config)
          connection (:connection (service-context this))
          endpoint (get-in config [:command-processing :mq :endpoint])
          command (if (string? command) command (command-names command))
          result (do-enqueue-command connection endpoint
                                     command version payload uuid)]
      ;; Obviously assumes that if do-* doesn't throw, msg is in
      (swap! (:stats (service-context this)) update :received-commands inc)
      result))

  (enqueue-raw-command [this raw-command uuid]
    (let [config (get-config)
          connection (:connection (service-context this))
          endpoint (get-in config [:command-processing :mq :endpoint])
          result (do-enqueue-raw-command connection endpoint raw-command uuid)]
      ;; Obviously assumes that if do-* doesn't throw, msg is in
      (swap! (:stats (service-context this)) update :received-commands inc)
      result))

  (response-pub [this]
    (-> this service-context :response-pub))

  (response-mult [this]
    (-> this service-context :response-mult)))
