;; ## REST Command endpoints

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
  [_ {:keys [params] :as request} _]
  (try
    (if-let [payload (get params "payload")]
      (annotated-return false {:annotate {:payload payload}})
      (pl-utils/return-json-error true "Missing payload"))
    (catch Exception e
      (pl-utils/return-json-error true (.getMessage e)))))

(defn http->mq
  [request graphdata]
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
