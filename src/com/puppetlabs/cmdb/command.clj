;; ## CMDB command handling
;;
;; Commands are the mechanism by which changes are made to the
;; CMDB's model of a population. Commands are represented by
;; `command objects`, which have the following JSON wire format:
;;
;;     {"command" "..."
;;      "version" 123
;;      "payload" <json object>}
;;
;; `payload` must be a valid JSON string of any sort. It's up to an individual
;; handler function how to interpret that object.
;;
;; The command object may also contain an optional "retries"
;; attribute that contains an integer number of times this message
;; has been re-enqueued for processing. The CMDB may discard
;; messages with retry counts that exceed its configured thresholds.
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

(ns com.puppetlabs.cmdb.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.cmdb.scf.storage :as scf-storage]
            [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clamq.protocol.consumer :as mq-cons]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn])
  (:use [slingshot.slingshot :only [try+ throw+]]
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
          (number? (:version %))]}
  (->> (json/parse-string command-string)
       (pl-utils/mapkeys keyword)))

;; ## Command processing exception classes

(defn fatality!
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

;; ## Command processors

(defmulti process-command!
  "Takes a command object and processes it to completion. Dispatch is
  based on the command's name and version information"
  (fn [{:keys [command version] :or {version 1}} _]
    [command version]))

;; Catalog replacement

(defmethod process-command! ["replace catalog" 1]
  [{:keys [payload]} options]
  ;; Parsing a catalog either works, or it generates a fatal exception
  (let [catalog  (try+
                  (cat/parse-from-json-string payload)
                  (catch Throwable e
                    (throw+ (fatality! e))))
        certname (:certname catalog)]
    (sql/with-connection (:db options)
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (scf-storage/replace-catalog! catalog))
    (log/info (format "[replace catalog] %s" certname))))

;; Fact replacement

(defmethod process-command! ["replace facts" 1]
  [{:keys [payload]} {:keys [db]}]
  (let [{:strs [name values]} (json/parse-string payload)]
    (sql/with-connection db
      (when-not (scf-storage/certname-exists? name)
        (scf-storage/add-certname! name))
      (scf-storage/replace-facts! name values))
    (log/info (format "[replace facts] %s" name))))

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
  [f on-failure]
  (fn [msg]
    (let [parse-result (try-parse-command msg)]
      (if (instance? Throwable parse-result)
        (do
          (mark! (global-metric :fatal))
          (on-failure parse-result))
        (f parse-result)))))

(defn wrap-with-discard
  "Wrap a message processor `f` such that incoming commands with a
  retry count exceeding `max-retries` are discarded.

  This assumes that all incoming messages are well-formed command
  objects, such as those produced by the `wrap-with-command-parser`
  middleware."
  [f max-retries]
  (fn [{:keys [command version retries] :or {retries 0} :as msg}]
    (let [cmd-metric #(get-in @metrics [command version %])]
      (create-metrics-for-command! command version)
      (mark! (cmd-metric :seen))
      (update! (global-metric :retry-counts) retries)
      (update! (cmd-metric :retry-counts) retries)

      (when (> retries max-retries)
        (mark! (global-metric :discarded))
        (mark! (cmd-metric :discarded)))

      (when (<= retries max-retries)
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

;; ### Fatal error callback

(defn handle-command-failure
  "Dump the error encountered during command-handling to the log"
  [msg e]
  (log/error "Fatal error processing msg" e))

;; ### Retry callback

(defn format-for-retry
  "Return a version of the supplied command message with its retry count incremented."
  [msg]
  {:post [(string? %)]}
  (let [retries (or (:retries msg) 0)]
    (json/generate-string (assoc msg :retries (inc retries)))))

(defn handle-command-retry
  "Dump the error encountered to the log, and re-publish the message
  with an incremented retry counter"
  [{:keys [command version] :as msg} e publish-fn]
  (mark! (get-in @metrics [command version :retried]))
  (log/error "Retrying message due to:" e)
  (publish-fn (format-for-retry msg)))

;; ### Principal function

(defn process-commands!
  "Connect to an MQ an continually, sequentially process commands.

  If the MQ consumption timeout is reached without any new data, the
  function will terminate."
  [connection endpoint options-map]
  (let [on-fatal   handle-command-failure
        producer   (mq-conn/producer connection)
        publish    #(mq-producer/publish producer endpoint %)
        on-retry   (fn [msg e] (handle-command-retry msg e publish))
        on-message (-> #(process-command! % options-map)
                       (wrap-with-discard 5)
                       (wrap-with-exception-handling on-retry on-fatal)
                       (wrap-with-command-parser on-fatal)
                       (wrap-with-meter (global-metric :seen))
                       (wrap-with-thread-name "command-proc"))]

    (let [mq-error (promise)
          consumer (mq-conn/consumer connection
                                     {:endpoint   endpoint
                                      :on-message on-message
                                      :transacted true
                                      :on-failure #(deliver mq-error (:exception %))})]
      (mq-cons/start consumer)

      ;; Block until an exception is thrown by the consumer thread
      (deref mq-error)
      (mq-cons/close consumer)
      (throw @mq-error))))
