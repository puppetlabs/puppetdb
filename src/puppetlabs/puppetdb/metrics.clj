(ns puppetlabs.puppetdb.metrics
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.metrics.server :as server]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :as compojure]))

(defservice metrics-service
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (let [app (->> (server/build-app #(:authorizer (shared-globals)))
                        (compojure/context (get-route this) []))]
           (log/info "Starting metrics server")
           (add-ring-handler this app)
           context)))
