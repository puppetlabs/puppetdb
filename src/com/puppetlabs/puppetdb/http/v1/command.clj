;; ## REST Command endpoints
;;
;; Commands can be submitted via HTTP, provided they conform to [the
;; submission guidelines](../spec/commands.md).
;;
;; If the command is intact and standards-compliant, we immediately
;; relay the command to the internal MQ for asynchronous processing.
;;
(ns com.puppetlabs.puppetdb.http.v1.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.utils :as pl-utils]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.middleware]
        [net.cgrand.moustache :only [app]]))

(defn enqueue-command
  [{:keys [params globals] :as request}]
  (let [uuid (command/enqueue-raw-command! (get-in globals [:command-mq :connection-string])
                                           (get-in globals [:command-mq :endpoint])
                                           (params "payload"))]
     (pl-http/json-response {:uuid uuid})))

(def command-app
  "Ring app for processing commands"
  (app
    [""]
    (-> enqueue-command
        verify-accepts-json
        verify-checksum
        (verify-param-exists "payload"))))
