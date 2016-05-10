(ns puppetlabs.pe-puppetdb-extensions.sync.pe-routing
  (:import [org.joda.time Period])
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.i18n.core :as i18n]
            [clout.core :as cc]
            [compojure.route :as route]
            [puppetlabs.trapperkeeper.services :as tksvc]
            [ring.middleware.resource :refer [wrap-resource resource-request]]
            [ring.util.request :as rreq]
            [ring.util.response :as rr]
            [puppetlabs.puppetdb.middleware :as mid]
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
            [puppetlabs.pe-puppetdb-extensions.catalogs :refer [turn-on-historical-catalogs!]]
            [puppetlabs.pe-puppetdb-extensions.reports
             :refer [reports-resources-routes turn-on-unchanged-resources!]]))

(defn pe-routes [get-config get-shared-globals query bucketed-summary-query enqueue-command response-mult]
  (map #(apply wrap-with-context %)
       (partition 2
                  ["/sync" (sync-svcs/sync-handler get-config query bucketed-summary-query enqueue-command response-mult get-shared-globals)
                   "/ext" (pe-server/build-app query get-shared-globals)])))

(tk/defservice pe-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer shared-globals query set-url-prefix]
   [:DefaultedConfig get-config]
   [:PuppetDBSync bucketed-summary-query]
   [:PuppetDBCommandDispatcher enqueue-command enqueue-raw-command response-pub response-mult]
   [:MaintenanceMode enable-maint-mode maint-mode? disable-maint-mode]
   [:PuppetDBStatus enable-status-service]]
  (init [this context]
        (let [context-root (get-route this)
              query-prefix (str context-root "/query")
              config (get-config)
              {sync-config :sync
               puppetdb-config :puppetdb
               jetty-config :jetty} config
              shared-with-prefix #(assoc (shared-globals) :url-prefix query-prefix)]
          (set-url-prefix query-prefix)
          (turn-on-unchanged-resources!)
          (turn-on-historical-catalogs!
           (:historical-catalogs-limit puppetdb-config 3))
          (add-ring-handler
           this
           (-> (pdb-app context-root
                        maint-mode?
                        (concat (reports-resources-routes shared-with-prefix)
                                (pdb-core-routes config
                                                 shared-with-prefix
                                                 enqueue-command
                                                 query
                                                 enqueue-raw-command
                                                 response-pub)
                                (pe-routes get-config shared-with-prefix
                                           query bucketed-summary-query enqueue-command (response-mult))))
               (mid/wrap-cert-authn (:certificate-whitelist puppetdb-config))
               mid/wrap-with-puppetdb-middleware)))
        (enable-maint-mode)
        (enable-status-service)
        context)
  (start [this context]
         (log/info "PuppetDB finished starting, disabling maintenance mode")
         (disable-maint-mode)
         context)

  (stop [this context]
        (enable-maint-mode)
        context))
