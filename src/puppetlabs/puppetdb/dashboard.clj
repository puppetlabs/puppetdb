(ns puppetlabs.puppetdb.dashboard
  (:require [clojure.tools.logging :as log]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-puppetdb-middleware]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.request :as rreq]
            [ring.util.response :as rr]
            [compojure.core :as compojure]))

(def dashboard
  (let [index-handler #(->> %
                            rreq/request-url
                            (format "%s/dashboard/index.html")
                            rr/redirect)]
    (-> (app [""] {:get index-handler})
        (wrap-resource "public"))))

(defservice dashboard-service
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (let [app (-> dashboard
                       (wrap-with-puppetdb-middleware (:authorizer (shared-globals))))]
           (log/info "Starting dashboard service")
           (->> app
                (compojure/context (get-route this) [])
                (add-ring-handler this))
           context)))

(def dashboard-redirect
  (app ["" &] {:get (fn [req] (rr/redirect "/pdb/dashboard/index.html"))}))

(defservice dashboard-redirect-service
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (log/info "Redirecting / to the PuppetDB dashboard")
         (->> dashboard-redirect
              (add-ring-handler this))
         context))
