;; ## PuppetDB command handling
;;
;; Commands are the mechanism by which changes are made to PuppetDB's
;; model of a population. Commands are represented by `command
;; objects`, which have the following JSON wire format:
;;
;;     {"command": "...",
;;      "version": 123,
;;      "payload": <json object>}
;;
;; `payload` must be a valid JSON string of any sort. It's up to an
;; individual handler function how to interpret that object.
;;
;; More details can be found in [the spec](../spec/commands.md).
;;
;; The command object may also contain an `annotations` attribute
;; containing a map with arbitrary keys and values which may have
;; command-specific meaning or may be used by the message processing
;; framework itself.
;;
;; Commands should include a `received` annotation containing a
;; timestamp of when the message was first seen by the system. If this
;; is omitted, it will be added when the message is first parsed, but
;; may then be somewhat inaccurate.
;;
;; Commands should include an `id` annotation containing a unique,
;; string identifier for the command. If this is omitted, it will be
;; added when the message is first parsed.
;;
;; Failed messages will have an `attempts` annotation containing an
;; array of maps of the form:
;;
;;     {:timestamp <timestamp>
;;      :error     "some error message"
;;      :trace     <stack trace from :exception>}
;;
;; Each entry corresponds to a single failed attempt at handling the
;; message, containing the error message, stack trace, and timestamp
;; for each failure. PuppetDB may discard messages which have been
;; attempted and failed too many times, or which have experienced
;; fatal errors (including unparseable messages).
;;
;; Failed messages will be stored in files in the "dead letter
;; office", located under the MQ data directory, in
;; `/discarded/<command>`. These files contain the annotated message,
;; along with each exception that occured while trying to handle the
;; message.
;;
;; We currently support the following wire formats for commands:
;;
;; 1. Java Strings
;;
;; 2. UTF-8 encoded byte-array
;;
;; In either case, the command itself, once string-ified, must be a
;; JSON-formatted string with the aforementioned structure.
;;

(ns com.puppetlabs.puppetdb.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.scf.storage :as scf-storage]
            [com.puppetlabs.puppetdb.catalog :as cat]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.puppetdb.command.dlo :as dlo]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clj-http.client :as client]
            [cheshire.core :as json]
            [clamq.protocol.consumer :as mq-cons]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn])
  (:use [slingshot.slingshot :only [try+ throw+]]
        [cheshire.custom :only (JSONable)]
        [clj-http.util :only [url-encode]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]]
        [metrics.meters :only (meter mark!)]
        [metrics.histograms :only (histogram update!)]
        [metrics.timers :only (timer time!)]))

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
;;         :discarded <meter>
;;         :processing-time <timer>
;;         :retry-counts <histogram>
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
;;           }
;;        }
;;     }
;;
;; The `"global"` hierarchy contains metrics aggregated across all
;; commands.
;;
;; * `:seen`: the number of commands (valid or not) encountered
;;
;; * `:processeed`: the number of commands successfully processed
;;
;; * `:fatal`: the number of fatal errors
;;
;; * `:retried`: the number of commands re-queued for retry
;;
;; * `:discarded`: the number of commands discarded for exceeding the
;;   maximum allowable retry count
;;
;; * `:processing-time`: how long it takes to process a command
;;
;; * `:retry-counts`: histogram containing the number of times
;;   messages have been retried prior to suceeding
;;

(def metrics (atom {}))
(def ns-str (str *ns*))

(defn create-metrics-for-command!
  "Create a subtree of metrics for the given command and version (if
  present).  If a subtree of metrics already exists, this function is
  a no-op."
  ([command]
     (create-metrics-for-command! command nil))
  ([command version]
     (let [prefix     (if (nil? version) [command] [command version])
           prefix-str (clojure.string/join "." prefix)]
       (when-not (get-in @metrics prefix)
         (swap! metrics assoc-in (conj prefix :processing-time) (timer [ns-str prefix-str "processing-time"]))
         (swap! metrics assoc-in (conj prefix :retry-counts) (histogram [ns-str prefix-str "retry-counts"]))
         (doseq [metric [:seen :processed :fatal :retried :discarded]
                 :let [metric-str (name metric)]]
           (swap! metrics assoc-in (conj prefix metric) (meter [ns-str prefix-str metric-str] "msgs/s")))))))

;; Create metrics for aggregate operations
(create-metrics-for-command! "global")

(defn global-metric
  "Returns the metric identified by `name` in the `\"global\"` metric
  hierarchy"
  [name]
  {:pre [(keyword? name)]}
  (get-in @metrics ["global" name]))

;; ## Command parsing

(defmulti parse-command
  "Take a wire-format command and parse it into a command object."
  class)

(defmethod parse-command (class (byte-array 0))
  [command-bytes]
  (parse-command (String. command-bytes "UTF-8")))

(defmethod parse-command String
  [command-string]
  {:pre  [(string? command-string)]
   :post [(map? %)
          (:payload %)
          (string? (:command %))
          (number? (:version %))
          (map? (:annotations %))]}
  (let [message     (json/parse-string command-string true)
        annotations (get message :annotations {})
        received    (get annotations :received (pl-utils/timestamp))
        id          (get annotations :id (pl-utils/uuid))
        annotations (-> annotations
                        (assoc :received received)
                        (assoc :id id))]
    (assoc message :annotations annotations)))

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
      (assoc-in [:annotations :received] (pl-utils/timestamp))
      (assoc-in [:annotations :id] (pl-utils/uuid))))

;; ## Command submission

(defn submit-command-via-http!
  "Submits `payload` as a valid command of type `command` and
  `version` to the PuppetDB instance specified by `host` and
  `port`. The `payload` will be converted to JSON before
  submission. Alternately accepts a command-map object (such as those
  returned by `parse-command`). Returns the server response."
  ([host port command version payload]
     {:pre [(string? command)
            (integer? version)]}
     (->> payload
          (assemble-command command version)
          (submit-command-via-http! host port)))
  ([host port command-map]
     {:pre [(string? host)
            (integer? port)
            (map? command-map)]}
     (let [message (json/generate-string command-map)
           body    (format "checksum=%s&payload=%s"
                           (pl-utils/utf8-string->sha1 message)
                           (url-encode message))
           url     (format "http://%s:%s/v2/commands" host port)]
       (client/post url {:body               body
                         :throw-exceptions   false
                         :content-type       :x-www-form-urlencoded
                         :character-encoding "UTF-8"
                         :accept             :json}))))

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
  (let [[msg id] (try
                   (let [cmd (-> raw-command
                                 (parse-command)
                                 (annotate-command))]
                     [(json/generate-string cmd) (get-in cmd [:annotations :id])])
                   (catch com.fasterxml.jackson.core.JsonParseException e
                     [raw-command (pl-utils/uuid)]))]
    (with-open [conn (mq/connect! mq-spec)]
      (mq/connect-and-publish! conn mq-endpoint msg))
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
  (with-open [conn (mq/connect! mq-spec)]
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

(defn fatal?
  "Tests if the supplied exception is a fatal command-processing
  exception or not."
  [exception]
  (:fatal exception))

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
  (fn [{:keys [command version] :or {version 1}} _]
    [command version]))

;; Catalog replacement

(defn replace-catalog*
  [{:keys [payload annotations version]} {:keys [db]}]
  (let [catalog (upon-error-throw-fatality (cat/parse-catalog payload version))
        certname (:certname catalog)
        id (:id annotations)
        timestamp (:received annotations)]
    (with-transacted-connection db
      (scf-storage/maybe-activate-node! certname timestamp)
      ;; Only store a catalog if it's newer than the current catalog
      (if-not (scf-storage/catalog-newer-than? certname timestamp)
        (scf-storage/replace-catalog! catalog timestamp)))
    (log/info (format "[%s] [%s] %s" id (command-names :replace-catalog) certname))))

(defmethod process-command! [(command-names :replace-catalog) 1]
  [{:keys [version payload] :as command} options]
  {:pre [(= version 1)]}
  (when-not (string? payload)
    (throw (IllegalArgumentException.
             (format "Payload for a '%s' v1 command must be a JSON string."
               (command-names :replace-catalog)))))
  (replace-catalog* command options))

(defmethod process-command! [(command-names :replace-catalog) 2]
  [{:keys [version] :as  command} options]
  {:pre [(= version 2)]}
  (replace-catalog* command options))

;; Fact replacement

(defmethod process-command! [(command-names :replace-facts) 1]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [{:strs [name] :as facts} (upon-error-throw-fatality (json/parse-string payload))
        id                       (:id annotations)
        timestamp                (:received annotations)]
    (with-transacted-connection db
      (scf-storage/maybe-activate-node! name timestamp)
      (if-not (scf-storage/facts-newer-than? name timestamp)
        (scf-storage/replace-facts! facts timestamp)))
    (log/info (format "[%s] [%s] %s" id (command-names :replace-facts) name))))

;; Node deactivation

(defmethod process-command! [(command-names :deactivate-node) 1]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [certname (upon-error-throw-fatality (json/parse-string payload))
        id       (:id annotations)]
    (with-transacted-connection db
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (scf-storage/deactivate-node! certname))
    (log/info (format "[%s] [%s] %s" id (command-names :deactivate-node) certname))))

;; Report submission

(defmethod process-command! [(command-names :store-report) 1]
  [{:keys [payload annotations]} {:keys [db]}]
  (let [id          (:id annotations)
        report      (upon-error-throw-fatality
                      (report/validate! payload))
        name        (:certname report)
        timestamp   (:received annotations)]
    (with-transacted-connection db
      (scf-storage/maybe-activate-node! name timestamp)
      (scf-storage/add-report! report timestamp))
    (log/info (format "[%s] [%s (EXPERIMENTAL!)] puppet v%s - %s"
                id (command-names :store-report)
                (:puppet-version report) (:certname report)))))

;; ## MQ I/O
;;
;; The data flow through the code is as follows:
;;
;; * A message is read off of an MQ endpoint
;;
;; * The message is fed through a _message processor_: a function that
;;   takes a message as the only argument
;;
;; * Repeat ad-infinitum
;;

;; ## MQ processing middleware
;;
;; The parsing and processing of incoming commands is architected as a
;; set of _middleware_ functions. That is, higher-order functions that
;; add capabilities to an existing message-handling
;; function. Middleware can be stacked, creating sophisticated
;; hierarchies of functionality. And because each middleware function
;; is isolated in terms of capability, testability is much simpler.
;;
;; It's not an original idea; it was stolen from _Ring's_ middleware
;; architecture.
;;

(defn wrap-with-meter
  "Wraps a message processor `f` and a `meter` such that `meter` will be marked
  for each invocation of `f`."
  [f meter]
  (fn [msg]
    (mark! meter)
    (f msg)))

(defn try-parse-command
  "Tries to parse `msg`, returning the parsed message if the parse is
  successful or a Throwable object if one is thrown."
  [msg]
  (try+
   (parse-command msg)
   (catch Throwable e
     e)))

(defn annotate-with-attempt
  "Adds an `attempt` annotation to `msg` indicating there was a failed attempt
  at handling the message, including the error and trace from `e`."
  [{:keys [annotations] :as msg} e]
  {:pre  [(map? annotations)]
   :post [(= (count (get-in % [:annotations :attempts]))
             (inc (count (:attempts annotations))))]}
  (let [attempts (get annotations :attempts [])
        attempt  {:timestamp (pl-utils/timestamp)
                  :error     (str e)
                  :trace     (map str (.getStackTrace e))}]
    (update-in msg [:annotations :attempts] conj attempt)))

(defn wrap-with-exception-handling
  "Wrap a message processor `f` such that all Throwable or `fatal?`
  exceptions are caught.

  If a `fatal?` exception is thrown, the supplied `on-fatal` function
  is invoked with the message and the exception as arguments.

  If any other Throwable exception is caught, the supplied `on-retry`
  function is invoked with the message and the exception as
  arguments."
  [f on-retry on-fatal]
  (fn [msg]
    (try+

     (mark! (global-metric :seen))
     (time! (global-metric :processing-time)
            (f msg))
     (mark! (global-metric :processed))

     (catch fatal? {:keys [cause]}
       (on-fatal msg cause)
       (mark! (global-metric :fatal)))

     (catch Throwable exception
       (on-retry msg exception)
       (mark! (global-metric :retried))))))

(defn wrap-with-command-parser
  "Wrap a message processor `f` such that all messages passed to `f` are
  well-formed commands. If a message cannot be parsed, the `on-fatal` hook is
  invoked, and `f` is ignored."
  [f on-parse-error]
  (fn [msg]
    (let [parse-result (try-parse-command msg)]
      (if (instance? Throwable parse-result)
        (do
          (mark! (global-metric :fatal))
          (on-parse-error msg parse-result))
        (f parse-result)))))

(defn wrap-with-discard
  "Wrap a message processor `f` such that incoming commands with a
  retry count exceeding `max-retries` are discarded.

  This assumes that all incoming messages are well-formed command
  objects, such as those produced by the `wrap-with-command-parser`
  middleware."
  [f on-discard max-retries]
  (fn [{:keys [command version annotations] :as msg}]
    (let [retries    (count (:attempts annotations))
          cmd-metric #(get-in @metrics [command version %])]
      (create-metrics-for-command! command version)
      (mark! (cmd-metric :seen))
      (update! (global-metric :retry-counts) retries)
      (update! (cmd-metric :retry-counts) retries)

      (when (>= retries max-retries)
        (mark! (global-metric :discarded))
        (mark! (cmd-metric :discarded))
        (on-discard msg))

      (when (< retries max-retries)
        (let [result (time! (cmd-metric :processing-time)
                            (f msg))]
          (mark! (cmd-metric :processed))
          result)))))

(defn wrap-with-thread-name
  "Wrap a message processor `f` such that the calling thread's name is
  set to `<prefix>-<thread-id>`. This is useful for situations where
  thread names are ordinarily duplicated across multiple threads, or
  if the default names aren't descriptive enough."
  [f prefix]
  (fn [msg]
    (let [t    (Thread/currentThread)
          name (format "%s-%d" prefix (.getId t))]
      (when-not (= (.getName t) name)
        (.setName t name))
      (f msg))))

;; ## High-level functions
;;
;; The following functions represent the principal logic for
;; command-processing.
;;

;; ### Parse error callback

(defn handle-command-discard
  [{:keys [command annotations] :as msg} discard]
  (let [attempts (count (:attempts annotations))
        id       (:id annotations)]
    (log/error (format "[%s] [%s] Exceeded max %d attempts" id command attempts))
    (discard msg nil)))

(defn handle-parse-error
  [msg e discard]
  (log/error e (format "Fatal error parsing command" msg))
  (discard msg e))

(defn handle-command-failure
  "Dump the error encountered during command-handling to the log and discard
  the message."
  [{:keys [command annotations] :as msg} e discard]
  (let [attempt (count (:attempts annotations))
        id      (:id annotations)
        msg     (annotate-with-attempt msg e)]
    (log/error e (format "[%s] [%s] Fatal error on attempt %d" id command attempt))
    (discard msg e)))

;; ### Retry callback

;; The number of times a message can be retried before we discard it
(def maximum-allowable-retries 16)

(defn handle-command-retry
  "Dump the error encountered to the log, and re-publish the message,
  with an incremented retry counter, after a delay.

  The error is logged to DEBUG level during the first `M/4` retries,
  and ERROR thereafter, where `M` is the maximum number of allowable
  retries.

  The retry delay is based on the following exponential backoff
  algorithm:

    2^(n-1) + random(2^n)

  `n` is the number of the current attempt, and `random` is a random
  number between 0 and the argument."
  [{:keys [command version annotations] :as msg} e publish-fn]
  (mark! (get-in @metrics [command version :retried]))
  (let [attempt (count (:attempts annotations))
        id      (:id annotations)
        msg     (annotate-with-attempt msg e)
        n       (inc attempt)
        delay   (+ (Math/pow 2 (dec n))
                   (rand-int (Math/pow 2 n)))
        logger  (if (> n (/ maximum-allowable-retries 4))
                  #(log/error %)
                  #(log/debug %))]
    (logger (format "[%s] [%s] Retrying after attempt %d, due to: %s"
                    id command attempt e))
    (publish-fn (json/generate-string msg) (mq/delay-property delay :seconds))))

;; ### Message handler

(defn produce-message-handler
  "Produce a message handler suitable for use by `process-commands!`. "
  [publish discarded-dir options-map]
  (let [discard        #(dlo/store-failed-message %1 %2 discarded-dir)
        on-discard     #(handle-command-discard % discard)
        on-parse-error #(handle-parse-error %1 %2 discard)
        on-fatal       #(handle-command-failure %1 %2 discard)
        on-retry       #(handle-command-retry %1 %2 publish)]
    (-> #(process-command! % options-map)
        (wrap-with-discard on-discard maximum-allowable-retries)
        (wrap-with-exception-handling on-retry on-fatal)
        (wrap-with-command-parser on-parse-error)
        (wrap-with-meter (global-metric :seen))
        (wrap-with-thread-name "command-proc"))))

;; ### Principal function

(defn process-commands!
  "Connect to an MQ an continually, sequentially process commands.

  If the MQ consumption timeout is reached without any new data, the
  function will terminate."
  [connection endpoint discarded-dir options-map]
  (let [producer   (mq-conn/producer connection)
        publish    (partial mq-producer/publish producer endpoint)
        on-message (produce-message-handler publish discarded-dir options-map)
        mq-error   (promise)
        consumer   (mq-conn/consumer connection
                                     {:endpoint   endpoint
                                      :on-message on-message
                                      :transacted true
                                      :on-failure #(deliver mq-error (:exception %))})]
    (mq-cons/start consumer)

    ;; Block until an exception is thrown by the consumer thread
    (try
      (deref mq-error)
      (throw @mq-error)
      (finally
        (mq-cons/close consumer)))))
