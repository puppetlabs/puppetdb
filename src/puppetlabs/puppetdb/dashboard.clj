(ns puppetlabs.puppetdb.dashboard
  (:require [clojure.tools.logging :as log]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [ring.util.response :as rr]))

(def dashboard-redirect
  (app ["" &] {:get (fn [req] (rr/redirect "/pdb/dashboard/index.html"))}))

(defservice dashboard-redirect-service
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (log/info "Redirecting / to the PuppetDB dashboard")
         (add-ring-handler this dashboard-redirect)
         context))
