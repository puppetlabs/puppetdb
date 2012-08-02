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
            [com.puppetlabs.http.utils :as pl-http-utils]
            [ring.util.response :as rr]))

(defn command-app
  "Ring app for processing commands"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "payload"))
   (pl-http-utils/error-response "missing payload")

   (and (params "checksum")
        (not= (params "checksum") (pl-utils/utf8-string->sha1 (params "payload"))))
   (pl-http-utils/error-response "checksums don't match")

   (not (pl-http-utils/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json")
       (rr/status 406))

   :else
   (let [uuid (command/enqueue-raw-command! (get-in globals [:command-mq :connection-string])
                                            (get-in globals [:command-mq :endpoint])
                                            (params "payload"))]
     (-> {:uuid uuid}
         (pl-http-utils/json-response)))))
