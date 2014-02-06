(ns com.puppetlabs.puppetdb.http.command
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.middleware :as mid]))

(defn enqueue-command
  "Enqueue the comman in the request parameters, return a UUID"
  [{:keys [params globals] :as request}]
  (let [uuid (command/enqueue-raw-command! (get-in globals [:command-mq :connection-string])
                                           (get-in globals [:command-mq :endpoint])
                                           (params "payload"))]
    (pl-http/json-response {:uuid uuid})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(def command
  "Function validating the request then submitting a command"
  (-> enqueue-command
      mid/verify-accepts-json
      mid/verify-checksum
      (mid/validate-query-params {:required ["payload"]
                              :optional ["checksum"]})))
