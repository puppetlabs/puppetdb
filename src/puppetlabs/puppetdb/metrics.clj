(ns puppetlabs.puppetdb.metrics
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.metrics.server :as server]
            [puppetlabs.trapperkeeper.core :refer [defservice main]]
            [compojure.core :as compojure]
            [puppetlabs.puppetdb.cli.services :refer [build-whitelist-authorizer]]))

(defservice metrics-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (let [authorizer (if-let [wl (-> (get-config)
                                          (get-in [:puppetdb :certificate-whitelist]))]
                            (build-whitelist-authorizer wl)
                            (constantly :authorized))
               app (server/build-app :authorizer authorizer)]
           (log/info "Starting metrics server")
           (add-ring-handler this (compojure/context (get-route this) [] app))
           context)))
