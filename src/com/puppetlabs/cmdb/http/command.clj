;; ## REST Command endpoints
;;
;; Commands can be submitted via HTTP, provided the following criteria
;; are met:
;;
;; * A `POST` is used
;;
;; * The `POST` contains a single parameter, `payload`
;;
;; * The `POST` is sent using a content type of
;;   `application/x-www-form-urlencoded`
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
;; * Contains a JSON object of `{:error "error message"}` if an error
;;   occurred.

(ns com.puppetlabs.cmdb.http.command
  (:require [clojure.contrib.logging :as log]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clamq.protocol.producer :as mq-producer]
            [clamq.protocol.connection :as mq-conn])
  (:use [clothesline.protocol.test-helpers :only [annotated-return]]
        [clothesline.service.helpers :only [defhandler]]))

(defn malformed-request?
  "A command submission is malformed if it lacks a `payload`
  attribute"
  [_ {:keys [params] :as request} _]
  (try
    (if-let [payload (get params "payload")]
      (annotated-return false {:annotate {:payload payload}})
      (pl-utils/return-json-error true "Missing payload"))
    (catch Exception e
      (pl-utils/return-json-error true (.getMessage e)))))

(defn http->mq
  "Takes a command object out of `graphdata`, and submits it to an MQ.

  If successful, this function returns a JSON `true`. Otherwise, an
  exception is thrown."
  [request graphdata]
  {:pre  [(:payload graphdata)]
   :post [(string? %)]}
  (let [mq-spec     (get-in request [:globals :command-mq :connection-string])
        mq-endpoint (get-in request [:globals :command-mq :endpoint])]
    (with-open [conn (mq/connect! mq-spec)]
      (let [producer (mq-conn/producer conn)]
        (mq-producer/publish producer mq-endpoint (:payload graphdata))))
    (json/generate-string true)))

(defhandler http->mq-handler
  :allowed-methods (constantly #{:post})
  :malformed-request? malformed-request?
  :content-types-provided (constantly {"application/json" http->mq})
  :content-types-accepted (constantly {"application/x-www-form-urlencoded" nil}))
