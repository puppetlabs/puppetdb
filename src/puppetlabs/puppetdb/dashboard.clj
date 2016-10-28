(ns puppetlabs.puppetdb.dashboard
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [ring.util.response :as rr]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.i18n.core :refer [trs]]))

(def dashboard-routes
  (cmdi/context "/"
                (cmdi/GET "" []
                          (rr/redirect "/pdb/dashboard/index.html"))))

(defservice dashboard-redirect-service
  [[:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (log/info (trs "Redirecting / to the PuppetDB dashboard"))
         (add-ring-handler this (mid/make-pdb-handler dashboard-routes))
         context))
