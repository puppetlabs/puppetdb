(ns puppetlabs.pe-puppetdb-extensions.sync.pe-routing
  (:import [org.joda.time Period])
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
            [puppetlabs.puppetdb.http.server :as server]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.pdb-routing :as pdb-route]
            [puppetlabs.pe-puppetdb-extensions.server :as pe-server]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as sync-svcs]
            [puppetlabs.puppetdb.time :refer [parse-period]]))


(defn pe-routes [get-config get-shared-globals query submit-command]
  (let [auth #(:authorizer (get-shared-globals))]
    (map #(apply pdb-route/wrap-with-context %)
         (partition 2
                    ["/sync" (sync-svcs/sync-app get-config query submit-command)
                     "/ext" (pe-server/build-app query auth)]))))

(tk/defservice pe-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer shared-globals query]
   [:ConfigService get-config]
   [:PuppetDBSync]
   [:PuppetDBCommand submit-command]
   [:PuppetDBCommandDispatcher enqueue-command enqueue-raw-command response-pub]
   [:MaintenanceMode enable-maint-mode maint-mode? disable-maint-mode]]
  (init [this context]
        (let [{node-ttl :node-ttl, sync-config :sync, jetty-config :jetty} (get-config)
              node-ttl (or (some-> node-ttl parse-period)
                           Period/ZERO)]
          (add-ring-handler this (pdb-route/pdb-app (get-route this)
                                                    maint-mode?
                                                    (concat (pdb-route/pdb-core-routes shared-globals
                                                                                       submit-command
                                                                                       query
                                                                                       enqueue-raw-command
                                                                                       response-pub)
                                                            (pe-routes get-config shared-globals query submit-command)))))
        (enable-maint-mode)
        context)
  (start [this context]
         (disable-maint-mode)
         context))
