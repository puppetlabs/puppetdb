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
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.utils :as pl-utils]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr]))

(defn command-app
  "Ring app for processing commands"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "payload"))
   (pl-http/error-response "missing payload")

   (and (params "checksum")
        (not= (params "checksum") (pl-utils/utf8-string->sha1 (params "payload"))))
   (pl-http/error-response "checksums don't match")

   (not (pl-http/acceptable-content-type
         "application/json"
         (headers "accept")))
   (rr/status (rr/response "must accept application/json")
              pl-http/status-not-acceptable)

   :else
   (let [uuid (command/enqueue-raw-command! (get-in globals [:command-mq :connection-string])
                                            (get-in globals [:command-mq :endpoint])
                                            (params "payload"))]
     (pl-http/json-response {:uuid uuid}))))
