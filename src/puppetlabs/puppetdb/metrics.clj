(ns puppetlabs.puppetdb.metrics
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.metrics.server :as server]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :as compojure]
            [puppetlabs.puppetdb.config :as conf]))

(defservice metrics-service
  [[:DefaultedConfig get-config]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (let [defaulted-config (get-config)]
           (log/info "Starting metrics server")
           (->> (server/build-app (get-in defaulted-config [:puppetdb :certificate-whitelist]))
                (compojure/context (get-route this) [])
                (add-ring-handler this))
           context)))
