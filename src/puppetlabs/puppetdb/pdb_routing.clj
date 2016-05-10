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
            [clj-http.client :as client]
            [puppetlabs.puppetdb.http.server :as server]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.middleware :as mid]
            [trptcolin.versioneer.core :as versioneer]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.status :as pdb-status]))


(defn resource-request-handler [req]
  (resource-request req "public"))

(defn maint-mode-handler [maint-mode-fn]
  (fn [req]
    (when (maint-mode-fn)
      (log/info "HTTP request received while in maintenance mode")
      {:status 503
       :body "PuppetDB is currently down. Try again later."})))

(defn wrap-with-context [uri route]
  (compojure/context uri [] route))

(defn pdb-core-routes [defaulted-config get-shared-globals enqueue-command-fn
                       query-fn enqueue-raw-command-fn response-pub]
  (let [db-cfg #(select-keys (get-shared-globals) [:scf-read-db])
        get-response-pub #(response-pub)]
    (map #(apply wrap-with-context %)
         (partition
          2
          ;; The remaining get-shared-globals args are for wrap-with-globals.
          ["/" (fn [req]
                 (->> req
                      rreq/request-url
                      (format "%s/dashboard/index.html")
                      rr/redirect))
           "/meta" (meta/build-app db-cfg defaulted-config)
           "/cmd" (cmd/command-app get-shared-globals
                                   enqueue-raw-command-fn
                                   get-response-pub
                                   (conf/reject-large-commands? defaulted-config)
                                   (conf/max-command-size defaulted-config))
           "/query" (server/build-app get-shared-globals)
           "/admin" (admin/build-app enqueue-command-fn query-fn db-cfg)]))))

(defn pdb-app [root maint-mode-fn app-routes]
  (compojure/context root []
    resource-request-handler
    (maint-mode-handler maint-mode-fn)
    (apply compojure/routes
           (concat app-routes
                   [(route/not-found "Not Found")]))))

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

;; This is vendored from the tk-status-service because version checking fails
;; semver validation on PDB snapshots. When we address this upstream we can put
;; the tk version back in.
(defn get-artifact-version
  [group-id artifact-id]
  (let [version (versioneer/get-version group-id artifact-id)]
    (when (empty? version)
      (throw (IllegalStateException.
               (format "Unable to find version number for '%s/%s'"
                 group-id
                 artifact-id))))
    version))

(tk/defservice pdb-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer shared-globals query set-url-prefix]
   [:PuppetDBCommandDispatcher
    enqueue-command enqueue-raw-command response-pub]
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

          (log/info "Starting PuppetDB, entering maintenance mode")
          (add-ring-handler
           this
           (-> (pdb-app context-root
                        maint-mode?
                        (pdb-core-routes config
                                         augmented-globals
                                         enqueue-command
                                         query
                                         enqueue-raw-command
                                         response-pub))
               (mid/wrap-cert-authn cert-whitelist)
               mid/wrap-with-puppetdb-middleware))

          (enable-maint-mode)
          (register-status "puppetdb-status"
                           (get-artifact-version "puppetlabs" "puppetdb")
                           1
                           (fn [level]
                             (pdb-status/create-status-map
                              (pdb-status/status-details config shared-globals maint-mode?)))))
        context)
  (start [this context]
         (log/info "PuppetDB finished starting, disabling maintenance mode")
         (disable-maint-mode)
         context)

  (stop [this context]
        (enable-maint-mode)))
