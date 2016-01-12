(ns puppetlabs.puppetdb.dashboard
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [ring.util.response :as rr]
            [puppetlabs.comidi :as cmdi]))

(def dashboard-routes
  (cmdi/context "/"
                (cmdi/GET "" []
                          (rr/redirect "/pdb/dashboard/index.html"))))

(defservice dashboard-redirect-service
  [[:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (log/info "Redirecting / to the PuppetDB dashboard")
         (add-ring-handler this (mid/make-pdb-handler dashboard-routes))
         context))
