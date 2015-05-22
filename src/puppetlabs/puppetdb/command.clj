(ns puppetlabs.puppetdb.command
  (:require [clojure.tools.logging :as log]
            [net.cgrand.moustache :as moustache]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :as compojure]
            [puppetlabs.puppetdb.command.core :as command]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.middleware :as mid]))

(defn enqueue-command
  "Enqueues the command in request and returns a UUID"
  [{:keys [connection endpoint]}]
  (fn [{:keys [body-string] :as request}]
    (let [uuid (command/enqueue-raw-command! connection endpoint body-string)]
      (http/json-response {:uuid uuid}))))

(defn routes
  [command-mq]
  (moustache/app ["v1"] {:any (enqueue-command command-mq)}))

(defn build-app
  "Function validating the request then submitting a command"
  [{:keys [authorizer command-mq]}]
  (-> (routes command-mq)
      mid/verify-accepts-json
      mid/verify-checksum
      (mid/validate-query-params {:optional ["checksum"]})
      mid/payload-to-body-string
      (mid/verify-content-type ["application/json"])
      (mid/wrap-with-puppetdb-middleware authorizer)
      (mid/wrap-with-metrics (atom {}) http/leading-uris)))

(defprotocol PuppetDBCommand
  (submit-command [this command version payload]))

(defservice command-service
  PuppetDBCommand
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (log/info "Starting command service")
         (->> (build-app (shared-globals))
              (compojure/context (get-route this) [])
              (add-ring-handler this))
         context)
  (submit-command [this command version payload]
                  (let [{{:keys [connection endpoint]} :command-mq} (shared-globals)]
                    (command/enqueue-command! connection endpoint (command-names command) version payload))))
