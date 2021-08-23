(ns puppetlabs.puppetdb.pdb-routing
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.utils :refer [call-unless-shutting-down]]
            [puppetlabs.trapperkeeper.services :as tksvc]
            [ring.middleware.resource :refer [resource-request]]
            [ring.util.request :as rreq]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [ring.util.response :as rr]
            [puppetlabs.puppetdb.meta :as meta]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.http.command :as cmd]
            [puppetlabs.puppetdb.http.server :as server]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.status :as pdb-status]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.puppetdb.dashboard :as dashboard]))


(defn resource-request-handler [req]
  (resource-request req "public"))

(defn maint-mode-handler [maint-mode-fn]
  (fn [req]
    (when (maint-mode-fn)
      {:status 503
       :body (tru "PuppetDB is currently down. Try again later.")})))

(defn wrap-with-context [uri route]
  (compojure/context uri [] route))

(defn pdb-core-routes [defaulted-config get-shared-globals enqueue-command-fn
                       query-fn clean-fn delete-node-fn]
  (let [db-cfg #(select-keys (get-shared-globals) [:scf-read-db])]
    (map #(apply wrap-with-context %)
         (partition
          2
          ;; The remaining get-shared-globals args are for wrap-with-globals.
          ["/" (fn [req]
                 (->> req
                      rreq/request-url
                      (format "%s/dashboard/index.html")
                      rr/redirect))
           "/dashboard" (dashboard/build-app dashboard/default-meter-defs)
           "/meta" (meta/build-app db-cfg defaulted-config)
           "/cmd" (cmd/command-app get-shared-globals
                                   enqueue-command-fn
                                   (conf/reject-large-commands? defaulted-config)
                                   (conf/max-command-size defaulted-config))
           "/query" (server/build-app get-shared-globals)
           ;; FIXME: /admin/cmd read-db?
           "/admin" (admin/build-app enqueue-command-fn
                                     query-fn
                                     db-cfg
                                     clean-fn
                                     delete-node-fn)]))))

(defn pdb-app [root maint-mode-fn app-routes]
  (compojure/context root []
    resource-request-handler
    (maint-mode-handler maint-mode-fn)
    (apply compojure/routes
           (concat app-routes
                   [(route/not-found (tru "Not Found"))]))))

(defn throw-unless-ready [context]
  (when-not (seq context)
    (throw (IllegalStateException. (trs "Service has not started")))))

(defprotocol MaintenanceMode
  (enable-maint-mode [this])
  (disable-maint-mode [this])
  (maint-mode? [this]))

(tk/defservice maint-mode-service
  MaintenanceMode
  [[:ShutdownService get-shutdown-reason]]

  (init
   [this context]
   ;; Do this even if we're shutting down.
   (assoc context ::maint-mode? (atom true)))

  (enable-maint-mode
   [this]
   (when-let [mode (::maint-mode? (tksvc/service-context this))]
     (reset! mode true)))

  (disable-maint-mode
   [this]
   (let [context (tksvc/service-context this)]
     (throw-unless-ready context)
     (update context ::maint-mode? reset! false)))

  (maint-mode?
   [this]
   ;; The service context might also be nil if this is called before
   ;; init, which can happen in various cases, including being called
   ;; from some stop method during a short-circuit shutdown.
   (let [maint-mode-atom (::maint-mode? (tksvc/service-context this) ::not-found)]
     ;;There's a small gap in time after the routes have
     ;;been added but before the TK service context gets
     ;;updated. This handles that and counts it as the app
     ;;being in maintenance mode
     (if (= ::not-found maint-mode-atom)
       true
       @maint-mode-atom))))

(defn init-pdb-routing
  [service context config context-root shared-globals
   set-url-prefix add-ring-handler maint-mode? enable-maint-mode
   query enqueue-command clean delete-node register-status]
  (let [query-prefix (str context-root "/query")
        augmented-globals #(-> (shared-globals)
                               (assoc :url-prefix query-prefix
                                      :warn-experimental true))
        cert-allowlist (get-in config [:puppetdb :certificate-allowlist])]
    (set-url-prefix query-prefix)

    (log/info (trs "Starting PuppetDB, entering maintenance mode"))
    (add-ring-handler
     service
     (-> (pdb-app context-root
                  maint-mode?
                  (pdb-core-routes config
                                   augmented-globals
                                   enqueue-command
                                   query
                                   clean
                                   delete-node))
         (mid/wrap-cert-authn cert-allowlist)
         mid/wrap-with-puppetdb-middleware))

    (enable-maint-mode)
    (pdb-status/register-pdb-status
     register-status
     (fn [level]
       (pdb-status/create-status-map
        (pdb-status/status-details config shared-globals maint-mode?)))))
  context)

(defn start-pdb-routing
  [context config disable-maint-mode]
  (when-not (get-in config [:global :upgrade-and-exit?])
    (log/info (trs "PuppetDB finished starting, disabling maintenance mode"))
    (disable-maint-mode))
  context)

(tk/defservice pdb-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer clean delete-node shared-globals query set-url-prefix]
   [:PuppetDBCommandDispatcher enqueue-command]
   [:MaintenanceMode enable-maint-mode maint-mode? disable-maint-mode]
   [:DefaultedConfig get-config]
   [:StatusService register-status]
   [:ShutdownService get-shutdown-reason]]

  (init
   [this context]
   (call-unless-shutting-down
    "PuppetDB routing service init" (get-shutdown-reason) context
    #(init-pdb-routing this context (get-config) (get-route this)
                       shared-globals
                       set-url-prefix add-ring-handler
                       maint-mode? enable-maint-mode
                       query enqueue-command
                       clean delete-node
                       register-status)))

  (start
   [this context]
   (call-unless-shutting-down
    "PuppetDB routing service start" (get-shutdown-reason) context
    #(start-pdb-routing context (get-config) disable-maint-mode)))

  (stop
   [this context]
   (enable-maint-mode)
   context))
