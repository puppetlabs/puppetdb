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
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.utils :as utils]
            [slingshot.slingshot :refer [try+ throw+]]
            [cheshire.custom :refer [JSONable]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.services :refer [defservice]]
            [schema.core :as s]))

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
  [message]
  {:pre  [(map? message)]
   :post [(map? %)]}
  (-> message
      (assoc-in [:annotations :received] (kitchensink/timestamp))
      (assoc-in [:annotations :id] (kitchensink/uuid))))

;; ## Command submission

(defn enqueue-raw-command!
  "Takes the given command and submits it to the `mq-endpoint`
  location on the MQ identified by `mq-spec`. We will annotate the
  command (see `annotate-command`) prior to submission.

  If the given command can't be parsed, we submit it as-is without
  annotating."
  [mq-spec mq-endpoint raw-command]
  {:pre  [(string? mq-spec)
          (string? mq-endpoint)
          (string? raw-command)]
   :post [(string? %)]}
  (let [id (kitchensink/uuid)]
    (with-open [conn (mq/activemq-connection mq-spec)]
      (mq/connect-and-publish! conn mq-endpoint raw-command {"received" (kitchensink/timestamp)
                                                             "id" id}))
    id))

(defn enqueue-command!
  "Takes the given command and submits it to the specified endpoint on
  the indicated MQ.

  If successful, this function returns a map containing the command's unique
  id."
  [mq-spec mq-endpoint command version payload]
  {:pre  [(string? mq-spec)
          (string? mq-endpoint)
          (string? command)
          (number? version)]
   :post [(string? %)]}
  (with-open [conn (mq/activemq-connection mq-spec)]
    (let [command-map (annotate-command (assemble-command command version payload))]
      (mq/publish-json! conn mq-endpoint command-map)
      (get-in command-map [:annotations :id]))))

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
  (let [catalog (upon-error-throw-fatality (cat/parse-catalog payload version))
        certname (:name catalog)
        id (:id annotations)
        timestamp (:received annotations)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! certname timestamp)
      ;; Only store a catalog if it's newer than the current catalog
      (if-not (scf-storage/catalog-newer-than? certname timestamp)
        (scf-storage/replace-catalog! catalog timestamp catalog-hash-debug-dir)))
    (log/info (format "[%s] [%s] %s" id (command-names :replace-catalog) certname))))

(defn warn-deprecated
  "Logs a deprecation warning message for the given `command` and `version`"
  [version command]
  (log/warn (format "command '%s' version %s is deprecated, use the latest version" command version)))

(defmethod process-command! [(command-names :replace-catalog) 6]
  [{:keys [version] :as command} options]
  (replace-catalog* command options))

;; Fact replacement

(defmethod process-command! [(command-names :replace-facts) 4]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [{:keys [name values] :as fact-data} payload
        id        (:id annotations)
        timestamp (:received annotations)
        fact-data (-> fact-data
                      (update-in [:values] utils/stringify-keys)
                      (update-in [:producer_timestamp] to-timestamp)
                      (assoc :timestamp timestamp)
                      upon-error-throw-fatality)]
    (jdbc/with-transacted-connection' db :repeatable-read
      (scf-storage/maybe-activate-node! name timestamp)
      (scf-storage/replace-facts! fact-data))
    (log/info (format "[%s] [%s] %s" id (command-names :replace-facts) name))))

;; Node deactivation

(defmethod process-command! [(command-names :deactivate-node) 2]
  [{certname :payload {:keys [id]} :annotations} {:keys [db]}]
  (jdbc/with-transacted-connection db
    (when-not (scf-storage/certname-exists? certname)
      (scf-storage/add-certname! certname))
    (scf-storage/deactivate-node! certname))
  (log/info (format "[%s] [%s] %s" id (command-names :deactivate-node) certname)))

;; Report submission

(defn store-report*
  [version db {:keys [payload annotations]}]
  (let [id          (:id annotations)
        report      (upon-error-throw-fatality
                     (report/validate! version payload))
        certname    (:certname report)
        timestamp   (:received annotations)]
    (jdbc/with-transacted-connection db
      (scf-storage/maybe-activate-node! certname timestamp)
      (scf-storage/add-report! report timestamp))
    (log/info (format "[%s] [%s] puppet v%s - %s"
                      id (command-names :store-report)
                      (:puppet_version report) (:certname report)))))

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
           (register-listener supported-command? #(process-command! % {:db scf-write-db
                                                                       :catalog-hash-debug-dir catalog-hash-debug-dir}))
           context)))
