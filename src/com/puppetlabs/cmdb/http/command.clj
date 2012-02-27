;; ## REST Command endpoints
;;
;; Commands can be submitted via HTTP, provided the following criteria
;; are met:
;;
;; * A `POST` is used
;;
;; * The `POST` contains a single parameter, `payload`
;;
;; * The `payload` paramater contains a string conforming to the
;;   structure of a command as outlined in
;;   `com.puppetlabs.cmdb.command`
;;
;; The response:
;;
;; * Has a content type of `application/json`
;;
;; * Contains a JSON object of `true` if the command was successfully
;;   submitted to the MQ
;;
(ns com.puppetlabs.cmdb.http.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn]
            [ring.util.response :as rr]))

(defn http->mq
  "Takes the given command and submits it to the specified endpoint on
  the indicated MQ.

  If successful, this function returns a JSON `true`."
  [payload mq-spec mq-endpoint]
  {:pre  [(string? payload)
          (string? mq-spec)
          (string? mq-endpoint)]
   :post [(string? %)]}
  (with-open [conn (mq/connect! mq-spec)]
    (let [producer (mq-conn/producer conn)]
      (mq-producer/publish producer mq-endpoint payload)))
  (json/generate-string true))

(defn command-app
  "Ring app for processing commands"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "payload"))
   (-> (rr/response "missing payload")
       (rr/status 400))

   (not (params "checksum"))
   (-> (rr/response "missing checksum")
       (rr/status 400))

   (not= (params "checksum") (pl-utils/utf8-string->sha1 (params "payload")))
   (-> (rr/response "checksums don't match")
       (rr/status 400))

   (not (pl-utils/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json")
       (rr/status 406))

   :else
   (-> (http->mq (params "payload")
                 (get-in globals [:command-mq :connection-string])
                 (get-in globals [:command-mq :endpoint]))
       (rr/response)
       (rr/header "Content-Type" "application/json")
       (rr/status 200))))
