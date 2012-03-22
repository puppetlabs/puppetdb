;; ## REST Command endpoints
;;
;; Commands can be submitted via HTTP, provided they conform to [the
;; submission guidelines](../spec/commands.md).
;;
;; If the command is intact and standards-compliant, we immediately
;; relay the command to the internal MQ for asynchronous processing.
;;
(ns com.puppetlabs.cmdb.http.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.cmdb.command :as command]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn]
            [ring.util.response :as rr])
  (:use  [slingshot.slingshot :only [try+ throw+]]))

(defn timestamp-message
  "Parses `message` and adds a `received` annotation indicating the time that
  we received the message. Returns the modified message in JSON format, or the
  original message if the message can't be parsed."
  [message]
  {:pre [(string? message)]
   :post [(string? %)]}
  (try+
    (let [message (command/parse-command message)]
      (-> message
        (assoc-in [:annotations :received] (pl-utils/timestamp))
        (json/generate-string)))
    (catch org.codehaus.jackson.JsonParseException e
      message)))

(defn http->mq
  "Takes the given command and submits it to the specified endpoint on
  the indicated MQ.

  If successful, this function returns `true`."
  [payload mq-spec mq-endpoint]
  {:pre  [(string? payload)
          (string? mq-spec)
          (string? mq-endpoint)]}
  (with-open [conn (mq/connect! mq-spec)]
    (let [producer (mq-conn/producer conn)
          message (timestamp-message payload)]
      (mq-producer/publish producer mq-endpoint message)))
  true)

(defn command-app
  "Ring app for processing commands"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "payload"))
   (pl-utils/error-response "missing payload")

   (not (params "checksum"))
   (pl-utils/error-response "missing checksum")

   (not= (params "checksum") (pl-utils/utf8-string->sha1 (params "payload")))
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
