(ns puppetlabs.pe-puppetdb-extensions.sync.pe-routing
  (:import [org.joda.time Period])
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clout.core :as cc]
            [compojure.core :as compojure]
            [compojure.route :as route]
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
            [puppetlabs.puppetdb.pdb-routing
             :refer [pdb-app pdb-core-routes wrap-with-context]]
            [puppetlabs.pe-puppetdb-extensions.server :as pe-server]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as sync-svcs]
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [puppetlabs.pe-puppetdb-extensions.reports
             :refer [reports-resources-routes turn-on-unchanged-resources!]]))

(defn pe-routes [get-config get-shared-globals query enqueue-command response-mult]
  (map #(apply wrap-with-context %)
       (partition 2
                  ["/sync" (sync-svcs/sync-app get-config query enqueue-command response-mult)
                   "/ext" (pe-server/build-app query)])))

(tk/defservice pe-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer shared-globals query set-url-prefix]
   [:DefaultedConfig get-config]
   [:PuppetDBSync]
   [:PuppetDBCommandDispatcher enqueue-command enqueue-raw-command response-pub response-mult]
   [:MaintenanceMode enable-maint-mode maint-mode? disable-maint-mode]]
  (init [this context]
        (let [context-root (get-route this)
              query-prefix (str context-root "/query")
              {node-ttl :node-ttl, sync-config :sync, jetty-config :jetty :as config} (get-config)
              node-ttl (or (some-> node-ttl parse-period)
                           Period/ZERO)
              shared-with-prefix #(assoc (shared-globals) :url-prefix query-prefix)]
          (set-url-prefix query-prefix)
          (log/info "Starting PuppetDB, entering maintenance mode")
          (turn-on-unchanged-resources!)
          (add-ring-handler
           this
           (pdb-app context-root config maint-mode?
                    (concat (reports-resources-routes shared-with-prefix)
                            (pdb-core-routes config
                                             shared-with-prefix
                                             enqueue-command
                                             query
                                             enqueue-raw-command
                                             response-pub)
                            (pe-routes get-config shared-with-prefix
                                       query enqueue-command (response-mult))
                            [(route/not-found "Not Found")]))))
        (enable-maint-mode)
        context)
  (start [this context]
         (log/info "PuppetDB finished starting, disabling maintenance mode")
         (disable-maint-mode)
         context))
