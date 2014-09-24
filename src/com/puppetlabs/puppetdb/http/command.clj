(ns com.puppetlabs.puppetdb.http.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.middleware :as mid]))

(defn enqueue-command
  "Enqueue the comman in the request parameters, return a UUID"
  [{:keys [body-string globals] :as request}]
  (let [uuid (command/enqueue-raw-command!
              (get-in globals [:command-mq :connection-string])
              (get-in globals [:command-mq :endpoint])
              body-string)]
    (pl-http/json-response {:uuid uuid})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(defn command-app
  "Function validating the request then submitting a command"
  [version]
  (case version
    :v1
    (throw (IllegalArgumentException. "No support for v1"))
    :v2
    (-> enqueue-command
        mid/verify-accepts-json
        mid/verify-checksum
        (mid/validate-query-params {:required ["payload"]
                                    :optional ["checksum"]})
        mid/payload-to-body-string
        (mid/verify-content-type ["application/x-www-form-urlencoded"]))
    :v3
    (-> enqueue-command
        mid/verify-accepts-json
        mid/verify-checksum
        (mid/validate-query-params {:optional ["checksum" "payload"]})
        mid/payload-to-body-string
        (mid/verify-content-type ["application/json" "application/x-www-form-urlencoded"]))
    (-> enqueue-command
        mid/verify-accepts-json
        mid/verify-checksum
        (mid/validate-query-params {:optional ["checksum"]})
        mid/payload-to-body-string
        (mid/verify-content-type ["application/json"]))))
