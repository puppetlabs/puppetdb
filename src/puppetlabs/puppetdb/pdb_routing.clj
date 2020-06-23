(ns puppetlabs.puppetdb.pdb-routing
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [puppetlabs.puppetdb.cheshire :as json]
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

(defprotocol MaintenanceMode
  (enable-maint-mode [this])
  (disable-maint-mode [this])
  (maint-mode? [this]))

(tk/defservice maint-mode-service
  MaintenanceMode
  []
  (init [this context]
        (assoc context ::maint-mode? (atom true)))
  (enable-maint-mode [this]
                     (-> (tksvc/service-context this)
                         (update ::maint-mode? reset! true)))
  (disable-maint-mode [this]
                      (-> (tksvc/service-context this)
                          (update ::maint-mode? reset! false)))
  (maint-mode? [this]
               (let [maint-mode-atom (::maint-mode? (tksvc/service-context this) ::not-found)]
                 ;;There's a small gap in time after the routes have
                 ;;been added but before the TK service context gets
                 ;;updated. This handles that and counts it as the app
                 ;;being in maintenance mode
                 (if (= ::not-found maint-mode-atom)
                   true
                   @maint-mode-atom))))

(tk/defservice pdb-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer clean delete-node shared-globals query set-url-prefix]
   [:PuppetDBCommandDispatcher enqueue-command]
   [:MaintenanceMode enable-maint-mode maint-mode? disable-maint-mode]
   [:DefaultedConfig get-config]
   [:StatusService register-status]]
  (init [this context]
        (let [context-root (get-route this)
              query-prefix (str context-root "/query")
              config (get-config)
              augmented-globals #(-> (shared-globals)
                                     (assoc :url-prefix query-prefix
                                            :warn-experimental true))
              cert-whitelist (get-in config [:puppetdb :certificate-whitelist])]
          (set-url-prefix query-prefix)

          (log/info (trs "Starting PuppetDB, entering maintenance mode"))
          (add-ring-handler
           this
           (-> (pdb-app context-root
                        maint-mode?
                        (pdb-core-routes config
                                         augmented-globals
                                         enqueue-command
                                         query
                                         clean
                                         delete-node))
               (mid/wrap-cert-authn cert-whitelist)
               mid/wrap-with-puppetdb-middleware))

          (enable-maint-mode)
          (pdb-status/register-pdb-status register-status
                                          (fn [level]
                                            (pdb-status/create-status-map
                                             (pdb-status/status-details config shared-globals maint-mode?)))))
        context)
  (start [this context]
         (when-not (get-in (get-config) [:global :upgrade-and-exit?])
           (log/info (trs "PuppetDB finished starting, disabling maintenance mode"))
           (disable-maint-mode))
         context)

  (stop [this context]
        (enable-maint-mode)))
