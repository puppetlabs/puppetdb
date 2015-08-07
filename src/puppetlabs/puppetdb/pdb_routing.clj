(ns puppetlabs.puppetdb.pdb-routing
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clout.core :as cc]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.services :as tksvc]
            [ring.middleware.resource :refer [wrap-resource resource-request]]
            [ring.util.request :as rreq]
            [ring.util.response :as rr]
            [puppetlabs.puppetdb.meta :as meta]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.http.command :as cmd]
            [puppetlabs.puppetdb.cli.services :as query]
            [puppetlabs.puppetdb.http.server :as server]))


(defn resource-request-handler [req]
  (resource-request req "public"))

(defn pdb-core-routes [shared-globals submit-command-fn query-fn enqueue-raw-command-fn response-pub]
  (map (fn [[uri route]]
         (compojure/context uri [] route))
       (partition 2 ["/meta" (meta/build-app shared-globals)
                     "/cmd" (cmd/command-app shared-globals enqueue-raw-command-fn (response-pub))
                     "/query" (server/build-app shared-globals)
                     "/admin" (admin/build-app submit-command-fn query-fn shared-globals)])))

(defn pdb-app [root app-routes]
  (compojure/context root []
                     resource-request-handler
                     (compojure/GET "/" req
                                    (->> req
                                         rreq/request-url
                                         (format "%s/dashboard/index.html")
                                         rr/redirect))
                     (apply compojure/routes app-routes)))

(tk/defservice pdb-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer shared-globals query]
   [:PuppetDBCommand submit-command]
   [:PuppetDBCommandDispatcher enqueue-command enqueue-raw-command response-pub]]
  (init [this context]
        (assoc context ::pdb-routing (atom {})))
  (start [this context]
         (add-ring-handler this (pdb-app (get-route this)
                                         (pdb-core-routes (shared-globals) submit-command query enqueue-raw-command response-pub)))
         context))
