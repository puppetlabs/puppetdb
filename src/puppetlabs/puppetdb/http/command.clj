(ns puppetlabs.puppetdb.http.command
  (:require [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.http :as http]
            [net.cgrand.moustache :as moustache]
            [puppetlabs.puppetdb.middleware :as mid]
            [compojure.core :as compojure]))

(defn enqueue-command-handler
  "Enqueues the command in request and returns a UUID"
  [connection endpoint]
  (fn [{:keys [body-string] :as request}]
    (let [uuid (command/enqueue-raw-command! connection endpoint body-string)]
      (http/json-response {:uuid uuid}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(defn command-app
  [{:keys [authorizer] :as globals}]
  (let [{{:keys [connection endpoint]} :command-mq} globals
        app (moustache/app
             ["v1" &] {:any (enqueue-command-handler connection endpoint)})]
    (-> app
        mid/verify-accepts-json
        mid/verify-checksum
        (mid/validate-query-params {:optional ["checksum"]})
        mid/payload-to-body-string
        (mid/verify-content-type ["application/json"])
        (mid/wrap-with-puppetdb-middleware authorizer)
        (mid/wrap-with-metrics (atom {}) http/leading-uris)
        (mid/wrap-with-globals globals))))

(defprotocol PuppetDBCommand
  (submit-command [this command version payload]))

(defservice puppetdb-command-service
  PuppetDBCommand
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (let [globals (shared-globals)
               url-prefix (get-route this)]
           (log/info "Starting command service")
           (->> (command-app globals)
                (compojure/context url-prefix [])
                (add-ring-handler this))
           context))

  (submit-command [this command version payload]
                  (let [{{:keys [connection endpoint]} :command-mq} (shared-globals)]
                    (command/enqueue-command! connection endpoint (command-names command) version payload))))
