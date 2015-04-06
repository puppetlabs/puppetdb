(ns puppetlabs.puppetdb.http.command
  (:require [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.middleware :as mid]))

(defn enqueue-command
  "Enqueues the command in request and returns a UUID"
  [{:keys [body-string globals] :as request}]
  (let [uuid (command/enqueue-raw-command!
              (get-in globals [:command-mq :connection])
              (get-in globals [:command-mq :endpoint])
              body-string)]
    (http/json-response {:uuid uuid})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(defn command-app
  "Function validating the request then submitting a command"
  [version]
  (-> enqueue-command
    mid/verify-accepts-json
    mid/verify-checksum
    (mid/validate-query-params {:optional ["checksum"]})
    mid/payload-to-body-string
    (mid/verify-content-type ["application/json"])))
