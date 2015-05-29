(ns puppetlabs.puppetdb.command.core
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
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [slingshot.slingshot :refer [try+ throw+]]
            [cheshire.custom :refer [JSONable]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.services :refer [defservice]]
            [schema.core :as s]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clj-time.core :refer [now]]))

;; ## Command parsing

(defmulti parse-command
  "Take a wire-format command and parse it into a command object."
  (comp class :body))

(defmethod parse-command (class (byte-array 0))
  [bytes-message]
  (parse-command (update bytes-message :body #(String. % "UTF-8"))))

(defn annotate-command
  "Annotate a command-map with a timestamp and UUID"
  [message & [received id]]
  {:pre  [(map? message)]
   :post [(map? %)]}
  (update message :annotations merge {:received (or received (kitchensink/timestamp))
                                      :id (or id (kitchensink/uuid))}))

(defmethod parse-command String
  [{:keys [headers body]}]
  {:pre  [(string? body)]
   :post [(map? %)
          (:payload %)
          (string? (:command %))
          (number? (:version %))
          (map? (:annotations %))]}
  (let [{:keys [received id]} headers]
    (-> (json/parse-string body true)
        (annotate-command received id))))

;; ## Command submission

(defn-validated enqueue-raw-command! :- s/Str
  "Submits raw-command to the mq-endpoint of mq-connection and returns
  its id."
  [mq-connection :- mq/connection-schema
   mq-endpoint :- s/Str
   raw-command :- s/Str]
  (let [id (kitchensink/uuid)
        received (kitchensink/timestamp)]
    (mq/send-message! mq-connection mq-endpoint raw-command {"received" received "id" id})
    id))

(defn-validated enqueue-command! :- s/Str
  "Submits command to the mq-endpoint of mq-connection and returns
  its id. Annotates the command via annotate-command."
  [mq-connection :- mq/connection-schema
   mq-endpoint :- s/Str
   command :- s/Str
   version :- s/Int
   payload :- pls/JSONable]
  (let [command-map (annotate-command {:command command
                                       :version version
                                       :payload payload})]
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
  (let [{certname :certname
         producer-timestamp :producer_timestamp
         :as catalog} (upon-error-throw-fatality (cat/parse-catalog payload version))
        {id :id
         received-timestamp :received} annotations]
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

(defmethod process-command! [(command-names :replace-catalog) 6]
  [command options]
  (replace-catalog* command options))

;; Fact replacement

(defmethod process-command! [(command-names :replace-facts) 4]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [{:keys [certname values] :as fact-data} payload
        {id :id
         received-timestamp :received} annotations
        fact-data (-> fact-data
                      (update :values utils/stringify-keys)
                      (update :producer_timestamp to-timestamp)
                      (assoc :timestamp received-timestamp)
                      upon-error-throw-fatality)
        producer-timestamp (:producer_timestamp fact-data)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname producer-timestamp)
      (scf-storage/replace-facts! fact-data))
    (log/infof "[%s] [%s] %s" id (command-names :replace-facts) certname)))

;; Node deactivation

(defmethod process-command! [(command-names :deactivate-node) 3]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [certname (:certname payload)
        producer-timestamp (to-timestamp (:producer_timestamp payload (now)))
        id (:id annotations)
        newer-record-exists? (fn [entity] (scf-storage/have-record-produced-after? entity certname producer-timestamp))]
    (jdbc/with-transacted-connection db
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (if (not-any? newer-record-exists? [:catalogs :factsets :reports])
        (scf-storage/deactivate-node! certname producer-timestamp)
        (log/warnf "Not deactivating node %s because local data is newer than %s." certname producer-timestamp)))
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

(defmethod process-command! [(command-names :store-report) 5]
  [{:keys [version] :as command} {:keys [db]}]
  (store-report* 5 db command))

(def supported-commands
  #{"replace facts" "replace catalog" "store report" "deactivate node"})

(def supported-command?
  (comp supported-commands :command))

(defservice command-service
  [[:PuppetDBServer shared-globals]
   [:MessageListenerService register-listener]]
  (start [this context]
         (let [{:keys [scf-write-db catalog-hash-debug-dir]} (shared-globals)]
           (register-listener supported-command?
                              #(process-command! % {:db scf-write-db
                                                    :catalog-hash-debug-dir catalog-hash-debug-dir}))
           context)))
