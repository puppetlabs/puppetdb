(ns puppetlabs.puppetdb.pdb-routing
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.trapperkeeper.services :as tksvc]
            [puppetlabs.puppetdb.mq :as mq]
            [ring.middleware.resource :refer [resource-request]]
            [ring.util.request :as rreq]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [ring.util.response :as rr]
            [puppetlabs.puppetdb.meta :as meta]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.http.command :as cmd]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.http.server :as server]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.kitchensink.core :as ks]))


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
  (let [meta-cfg #(select-keys (get-shared-globals) [:scf-read-db])
        get-response-pub #(response-pub)]
    (map #(apply wrap-with-context %)
         (partition
          2
          ;; The remaining get-shared-globals args are for wrap-with-globals.
          ["/meta" (meta/build-app meta-cfg defaulted-config)
           "/cmd" (cmd/command-app get-shared-globals
                                   enqueue-raw-command-fn get-response-pub)
           "/query" (server/build-app get-shared-globals)
           "/admin" (admin/build-app enqueue-command-fn query-fn)
           (route/not-found "Not Found")]))))

(defn pdb-app [root defaulted-config maint-mode-fn app-routes]
  (-> (compojure/context root []
                         resource-request-handler
                         (maint-mode-handler maint-mode-fn)
                         (compojure/GET "/" req
                                        (->> req
                                             rreq/request-url
                                             (format "%s/dashboard/index.html")
                                             rr/redirect))
                         (apply compojure/routes
                                (concat app-routes
                                        [(route/not-found "Not Found")])))
      (mid/wrap-with-puppetdb-middleware (get-in defaulted-config [:puppetdb :certificate-whitelist]))))

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
   [:PuppetDBServer shared-globals query set-url-prefix]
   [:PuppetDBCommandDispatcher
    enqueue-command enqueue-raw-command response-pub]
   [:MaintenanceMode enable-maint-mode maint-mode? disable-maint-mode]
   [:StatusService register-status]
   [:DefaultedConfig get-config]]
  (init [this context]
        (let [context-root (get-route this)
              query-prefix (str context-root "/query")
              config (get-config)
              augmented-globals #(-> (shared-globals)
                                     (assoc :url-prefix query-prefix)
                                     (assoc :warn-experimental true))]
          (set-url-prefix query-prefix)
          (log/info "Starting PuppetDB, entering maintenance mode")
          (add-ring-handler this (pdb-app context-root
                                          config
                                          maint-mode?
                                          (pdb-core-routes config
                                                           augmented-globals
                                                           enqueue-command
                                                           query
                                                           enqueue-raw-command
                                                           response-pub)))

          (enable-maint-mode)
          (register-status "puppetdb-status"
                           (status-core/get-artifact-version "puppetlabs" "puppetdb")
                           1
                           (fn [level]
                             (let [globals (shared-globals)
                                   queue-depth (->> [:command-processing :mq :endpoint]
                                                    (get-in config)
                                                    (mq/queue-size "localhost"))
                                   read-db-up? (sutils/db-up? (:scf-read-db globals))
                                   write-db-up? (sutils/db-up? (:scf-write-db globals))
                                   state (if (and read-db-up? write-db-up?)
                                           :running
                                           :error)]
                               {:state state
                                :status {:maintenance_mode? (maint-mode?)
                                         :queue_depth queue-depth
                                         :read_db_up? read-db-up?
                                         :write_db_up? write-db-up?}}))))
        context)
  (start [this context]
         (log/info "PuppetDB finished starting, disabling maintenance mode")
         (disable-maint-mode)
         context))
