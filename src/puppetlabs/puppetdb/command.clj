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
            [cheshire.custom :refer [JSONable]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.services
             :refer [defservice service-context]]
            [schema.core :as s]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clj-time.core :refer [now]]
            [clojure.set :as set]
            [clojure.core.async :as async]))

;; ## Command parsing

(defmulti parse-command
  "Take a wire-format command and parse it into a command object."
  (comp class :body))

(defmethod parse-command (class (byte-array 0))
  [bytes-message]
  (parse-command (update-in bytes-message [:body] #(String. % "UTF-8"))))

(defmethod parse-command String
  [{:keys [headers body]}]
  {:pre  [(string? body)]
   :post [(map? %)
          (:payload %)
          (string? (:command %))
          (number? (:version %))
          (map? (:annotations %))]}
  (let [message     (json/parse-string body true)
        received    (get headers :received (kitchensink/timestamp))
        id          (get headers :id (kitchensink/uuid))]
    (-> message
        (assoc-in [:annotations :received] received)
        (assoc-in [:annotations :id] id))))

(defn assemble-command
  "Builds a command-map from the supplied parameters"
  [command version payload]
  {:pre  [(string? command)
          (number? version)
          (satisfies? JSONable payload)]
   :post [(map? %)
          (:payload %)]}
  {:command command
   :version version
   :payload payload})

(defn annotate-command
  "Annotate a command-map with a timestamp and UUID"
  [message uuid]
  {:pre  [(map? message)]
   :post [(map? %)]}
  (-> message
      (assoc-in [:annotations :received] (kitchensink/timestamp))
      (assoc-in [:annotations :id] (or uuid (kitchensink/uuid)))))

;; ## Command submission

(defn-validated ^:private do-enqueue-raw-command :- s/Str
  "Submits raw-command to the mq-endpoint of mq-connection and returns
  its id."
  [mq-connection :- mq/connection-schema
   mq-endpoint :- s/Str
   raw-command :- s/Str
   uuid :- s/Str]
  (mq/send-message! mq-connection mq-endpoint
                    raw-command
                    {"received" (kitchensink/timestamp) "id" uuid})
  uuid)

(defn-validated ^:private do-enqueue-command :- s/Str
  "Submits command to the mq-endpoint of mq-connection and returns
  its id. Annotates the command via annotate-command."
  [mq-connection :- mq/connection-schema
   mq-endpoint :- s/Str
   command :- s/Str
   version :- s/Int
   payload
   uuid :- (s/maybe s/Str)]
  (let [command-map (annotate-command (assemble-command command version payload) uuid)]
    (mq/send-message! mq-connection mq-endpoint
                      (json/generate-string command-map))
    (get-in command-map [:annotations :id])))

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

;; ## Command processors

(defmulti process-command!
  "Takes a command object and processes it to completion. Dispatch is
  based on the command's name and version information"
  (fn [{:keys [command version]} _]
    [command version]))

;; Catalog replacement

(defn replace-catalog*
  [{:keys [payload annotations version]} {:keys [db catalog-hash-debug-dir]}]
  (let [received-timestamp (:received annotations)
        catalog (upon-error-throw-fatality (cat/parse-catalog payload version received-timestamp))
        certname (:certname catalog)
        id (:id annotations)
        producer-timestamp (:producer_timestamp catalog)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      ;; Only store a catalog if its producer_timestamp is <= the existing catalog's.
      (if-not (scf-storage/catalog-newer-than? certname producer-timestamp)
        (scf-storage/replace-catalog! catalog received-timestamp catalog-hash-debug-dir)
        (log/warnf "Not replacing catalog for certname %s because local data is newer." certname)))
    (log/infof "[%s] [%s] %s" id (command-names :replace-catalog) certname)))

(defn warn-deprecated
  "Logs a deprecation warning message for the given `command` and `version`"
  [version command]
  (log/warnf "command '%s' version %s is deprecated, use the latest version" command version))

(defmethod process-command! [(command-names :replace-catalog) 4]
  [command options]
  (replace-catalog* command options))

(defmethod process-command! [(command-names :replace-catalog) 5]
  [command options]
  (replace-catalog* command options))

(defmethod process-command! [(command-names :replace-catalog) 6]
  [command options]
  (replace-catalog* command options))

;; Fact replacement

(defmethod process-command! [(command-names :replace-facts) 4]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [{:keys [certname values] :as fact-data} payload
        id        (:id annotations)
        received-timestamp (:received annotations)
        fact-data (-> fact-data
                      (update-in [:values] utils/stringify-keys)
                      (update-in [:producer_timestamp] to-timestamp)
                      (assoc :timestamp received-timestamp)
                      upon-error-throw-fatality)
        producer-timestamp (:producer_timestamp fact-data)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/replace-facts! fact-data))
    (log/infof "[%s] [%s] %s" id (command-names :replace-facts) certname)))

(defmethod process-command! [(command-names :replace-facts) 3]
  [command config]
  (-> command
      fact/v3-wire->v4-wire
      (process-command! config)))

(defmethod process-command! [(command-names :replace-facts) 2]
  [command config]
  (let [received-time (get-in command [:annotations :received])]
    (-> command
        (fact/v2-wire->v4-wire received-time)
        (process-command! config))))

;; Node deactivation
(defmethod process-command! [(command-names :deactivate-node) 1]
  [command config]
  (-> command
      (assoc :version 2)
      (update :payload #(upon-error-throw-fatality (json/parse-string % true)))
      (process-command! config)))

(defmethod process-command! [(command-names :deactivate-node) 2]
  [command config]
  (-> command
      (assoc :version 3)
      (update :payload #(hash-map :certname %))
      (process-command! config)))

(defmethod process-command! [(command-names :deactivate-node) 3]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [certname (:certname payload)
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))
        id  (:id annotations)]
    (jdbc/with-transacted-connection db
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (scf-storage/deactivate-node! certname producer-timestamp))
    (log/infof "[%s] [%s] %s" id (command-names :deactivate-node) certname)))

;; Report submission

(defn store-report*
  [version db {:keys [payload annotations]}]
  (let [id (:id annotations)
        received-timestamp (:received annotations)
        {:keys [certname puppet_version] :as report}
        (->> payload
             (s/validate report/report-wireformat-schema)
             upon-error-throw-fatality)
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))]
    (jdbc/with-transacted-connection db
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/add-report! report received-timestamp))
    (log/infof "[%s] [%s] puppet v%s - %s"
               id (command-names :store-report)
               puppet_version certname)))

(defn- resource->skipped-resource-events
  "Fabricate a skipped resource-event"
  [resource]
  (-> resource
      ;; We also need to grab the timestamp when the resource is `skipped'
      (select-keys [:resource_type :resource_title :file :line :containment_path :timestamp])
      (merge {:status "skipped" :property nil :old_value nil :new_value nil :message nil})
      vector))

(defn- resource->resource-events
  [{:keys [skipped] :as resource}]
  (if (= skipped true)
    (resource->skipped-resource-events resource)
    (let [resource-metadata (select-keys resource [:resource_type :resource_title :file :line :containment_path])]
      (map (partial merge resource-metadata) (:events resource)))))

(defn- resources->resource-events
  [report]
  (-> report
      (update :resources (partial mapcat resource->resource-events))
      (clojure.set/rename-keys {:resources :resource_events})))

(defmethod process-command! [(command-names :store-report) 3]
  [{:keys [version] :as command} {:keys [db]}]
  (store-report* 5 db (-> command
                          (update :payload report/wire-v3->wire-v5 (get-in command [:annotations :received])))))

(defmethod process-command! [(command-names :store-report) 4]
  [{:keys [version] :as command} {:keys [db]}]
  (store-report* 5 db (-> command
                          (update :payload report/wire-v4->wire-v5 (get-in command [:annotations :received])))))

(defmethod process-command! [(command-names :store-report) 5]
  [{:keys [version] :as command} {:keys [db]}]
  (store-report* 5 db command))

(defmethod process-command! [(command-names :store-report) 6]
  [{:keys [version] :as command} {:keys [db]}]
  (store-report* 6 db (update command :payload resources->resource-events)))

(def supported-command?
  (comp (kitchensink/valset command-names) :command))

(defprotocol PuppetDBCommandDispatcher
  (enqueue-command
    [this connection endpoint command version payload]
    [this connection endpoint command version payload uuid]
    "Annotates the command via annotate-command, submits it to the
    endpoint of the connection, and then returns its unique id.")

  (enqueue-raw-command [this connection endpoint raw-command uuid]
    "Submits the raw-command to the endpoint of the connection and
    returns the command's unique id.")

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
  (merge {:id (-> cmd :annotations :id)}
         (when ex {:exception ex})))

(defn process-command-and-respond! [cmd config response-pub-chan stats-atom]
  (try
    (let [result (process-command! cmd config)]
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
  [[:PuppetDBServer shared-globals]
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
    (let [{:keys [scf-write-db catalog-hash-debug-dir]} (shared-globals)
          config {:db scf-write-db
                  :catalog-hash-debug-dir catalog-hash-debug-dir}
          {:keys [response-chan response-pub]} context]
      (register-listener
       supported-command?
       #(process-command-and-respond! % config response-chan (:stats context)))
      context))

  (stop [this context]
    (async/unsub-all (:response-pub context))
    (async/untap-all (:response-mult context))
    (async/close! (:response-chan-for-pub context))
    (async/close! (:response-chan context))
    (dissoc context :response-pub :response-chan :response-chan-for-pub :response-mult))

  (stats [this]
    @(:stats (service-context this)))

  (enqueue-command [this connection endpoint command version payload]
    (enqueue-command this connection endpoint command version payload nil))

  (enqueue-command [this connection endpoint command version payload uuid]
    (let [result (do-enqueue-command connection endpoint
                                     command version payload uuid)]


      ;; Obviously assumes that if do-* doesn't throw, msg is in
      (swap! (:stats (service-context this)) update :received-commands inc)
      result))

  (enqueue-raw-command [this connection endpoint raw-command uuid]
    (let [result (do-enqueue-raw-command connection endpoint raw-command uuid)]
      ;; Obviously assumes that if do-* doesn't throw, msg is in
      (swap! (:stats (service-context this)) update :received-commands inc)
      result))

  (response-pub [this]
    (-> this service-context :response-pub))

  (response-mult [this]
    (-> this service-context :response-mult)))
