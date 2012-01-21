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
;; `payload` must be a valid JSON object of any sort. It's up to an
;; individual handler function how to interpret that object.
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

(ns com.puppetlabs.cmdb.command
  (:require [clojure.contrib.logging :as log]
            [com.puppetlabs.cmdb.scf.storage :as scf-storage]
            [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clamq.protocol.seqable :as mq-seq]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn])
  (:use [slingshot.core :only [try+ throw+]]))

;; ### Command parsing

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

;; ### Command processing exception classes

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

;; ### Command processors

(defmulti process-command!
  "Takes a command object and processes it to completion. Dispatch is
  based on the command's name and version information"
  (fn [{:keys [command version] :or {version 1}} _]
    [command version]))

;; ### Catalog replacement

(defmethod process-command! ["replace catalog" 1] [cmd options]
  ;; Parsing a catalog either works, or it generates a fatal exception
  (let [catalog  (try+
                  (-> cmd
                      :payload
                      (cat/parse-from-json-obj))
                  (catch Throwable e
                    (throw+ (fatality! e))))
        certname (:certname catalog)]
    (sql/with-connection (:db options)
      (when-not (scf-storage/certname-exists? certname)
        (scf-storage/add-certname! certname))
      (scf-storage/replace-catalog! catalog))
    (log/info (format "[replace catalog] %s" certname))))

;; ### Message queue I/O and utilities

(defn command-map!
  "Event loop that maps `f` over the messages in `msg-seq`.

  `ack-msg` is a function that takes a message as an argument, and is
  called whenever the message is _acknowledged_, meaning that we're
  done processing the message.

  If `f` throws an `fatality!` object (testable using the `fatal?`
  function), then the supplied `on-fatal` function is called with the
  message and the exception as arguments. After the callback has
  finished executing, the message is acknowledged.

  If `f` throws any other type of exception, the supplied `on-retry`
  function is called with the message and the exception as
  arguments. The original message is then acknowledged, so `on-retry`
  should completely handle the retry scenario prior to returning
  control to the caller. There is a race condition here wherein a
  crash in-between the time `on-retry` has completed and before the
  original message is acknowledged may incur the side-effects from
  `on-retry`, yet the original message still exists at the head of the
  queue. This is a conscious trade-off versus losing the message
  altogether.

  Any exceptions thrown during invokation of a callback function will
  cause the entire function to terminate without acknowledging the
  currently-operated-on message."
  [msg-seq ack-msg f on-retry on-fatal]
  (doseq [msg msg-seq]
    (try+

     (f msg)
     (ack-msg msg)

     (catch fatal? {:keys [cause]}
       (on-fatal msg cause)
       (ack-msg msg))

     (catch Throwable exception
       (on-retry msg exception)
       (ack-msg msg)))))

(defn make-msg-handler
  "Returns a handler function (for use with command-map!) that attempts
  to parse and process a command.

  Parsing errors trigger a fatal exception, as if we can't even parse
  the command there's really no point in retrying the processing. If
  we encounter a message that's been retried more than `max-retries`
  times, we don't bother processing it."
  [max-retries options-map]
  (fn [msg]
    (let [parsed-msg (try+
                      (parse-command msg)
                      (catch Throwable e
                        (throw+ (fatality! e))))
          retry-count (get parsed-msg :retries 0)]
      (when (< retry-count max-retries)
        (process-command! parsed-msg options-map)))))

(defn handle-command-failure
  "Dump the error encountered during command-handling to the log"
  [msg e]
  (log/error "Fatal error processing msg" e))

(defn format-for-retry
  "Return a version of the supplied command message with its retry count incremented."
  [msg]
  {:post [(string? %)]}
  (let [{:keys [command version payload retries] :or {retries 0}} (parse-command msg)]
    (json/generate-string {"command" command
                           "version" version
                           "payload" payload
                           "retries" (inc retries)})))

(defn process-commands!
  "Connect to an MQ an continually, sequentially process commands.

  If the MQ consumption timeout is reached without any new data, the
  function will terminate."
  [connection endpoint options-map]
  (let [on-message (make-msg-handler 5 options-map)
        on-fatal   handle-command-failure
        producer   (mq-conn/producer connection)
        publish    #(mq-producer/publish producer endpoint %)
        on-retry   (fn [msg e] (publish (format-for-retry msg)))]

    (with-open [consumer (mq-conn/seqable connection {:endpoint endpoint :timeout 300000})]
      (let [ack-msg (fn [msg] (mq-seq/ack consumer))
            msg-seq (mq-seq/mseq consumer)]
        (command-map! msg-seq ack-msg on-message on-retry on-fatal)))))
