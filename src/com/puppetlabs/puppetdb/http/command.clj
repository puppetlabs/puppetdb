;; ## REST Command endpoints
;;
;; Commands can be submitted via HTTP, provided they conform to [the
;; submission guidelines](../spec/commands.md).
;;
;; If the command is intact and standards-compliant, we immediately
;; relay the command to the internal MQ for asynchronous processing.
;;
(ns com.puppetlabs.puppetdb.http.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn]
            [ring.util.response :as rr])
  (:use  [slingshot.slingshot :only [try+ throw+]]))

(defn format-for-submission
  "Readies the supplied wire-format command for submission.

  1. Parses `message` and adds a `received` annotation indicating the
  time that we received the message.

  2. Adds a unique identifier to the command

  Returns the modified message in JSON format, or the original message
  if the message can't be parsed."
  [message id]
  {:pre  [(string? message)
          (string? id)]
   :post [(string? %)]}
  (try+
   (let [message (command/parse-command message)]
     (-> message
         (assoc-in [:annotations :received] (pl-utils/timestamp))
         (assoc-in [:annotations :id] id)
         (json/generate-string)))
   (catch com.fasterxml.jackson.core.JsonParseException e
     message)))

(defn http->mq
  "Takes the given command and submits it to the specified endpoint on
  the indicated MQ.

  If successful, this function returns a map containing the command's unique
  id."
  [payload mq-spec mq-endpoint]
  {:pre  [(string? payload)
          (string? mq-spec)
          (string? mq-endpoint)]
   :post [(map? %)]}
  (with-open [conn (mq/connect! mq-spec)]
    (let [producer (mq-conn/producer conn)
          id       (pl-utils/uuid)
          message  (format-for-submission payload id)]
      (mq-producer/publish producer mq-endpoint message)
      {:uuid id})))

(defn command-app
  "Ring app for processing commands"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "payload"))
   (pl-utils/error-response "missing payload")

   (and (params "checksum")
        (not= (params "checksum") (pl-utils/utf8-string->sha1 (params "payload"))))
   (pl-utils/error-response "checksums don't match")

   (not (pl-utils/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json")
       (rr/status 406))

   :else
   (-> (http->mq (params "payload")
                 (get-in globals [:command-mq :connection-string])
                 (get-in globals [:command-mq :endpoint]))
       pl-utils/json-response)))
