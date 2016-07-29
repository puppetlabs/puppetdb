(ns puppetlabs.pe-puppetdb-extensions.sync.pe-routing
  (:import [org.joda.time Period])
  (:require [clout.core :as cc]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.rbac-client.middleware.authentication :as mid-authn]
            [puppetlabs.rbac-client.protocols.rbac :as prot-rbac]
            [puppetlabs.trapperkeeper.services :as tksvc]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.puppetdb.meta :as meta]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.http.command :as cmd]
            [puppetlabs.puppetdb.cli.services :as query]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.pdb-routing
             :refer [pdb-app pdb-core-routes wrap-with-context]]
            [puppetlabs.pe-puppetdb-extensions.server :as pe-server]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as sync-svcs]
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [puppetlabs.pe-puppetdb-extensions.catalogs
             :refer [turn-on-historical-catalogs!]]
            [puppetlabs.pe-puppetdb-extensions.reports
             :refer [reports-resources-routes turn-on-unchanged-resources!
                     enable-corrective-change!]]
            [puppetlabs.puppetdb.status :as pdb-status]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.pe-puppetdb-extensions.sync.status :as sync-status]
            [puppetlabs.puppetdb.utils :refer [dash->underscore-keys]]))

(def view-permission "nodes:view_data:*")
(def edit-permission "nodes:edit_data:*")

(defn rbac-permission
  [uri]
  (condp #(.startsWith %2 %1) uri
    "/pdb/meta" view-permission
    "/pdb/query" view-permission
    "/pdb/ext" view-permission
    "/pdb/sync" edit-permission
    "/pdb/admin" edit-permission
    "/pdb/cmd" edit-permission))

(defn wrap-cert-and-token-authn
  [cert-whitelist rbac-consumer-svc app]
  (if-let [cert-authorize-fn (some-> cert-whitelist mid/build-whitelist-authorizer)]
    (fn [{:keys [uri ssl-client-cn] :as req}]
      (if-let [cert-auth-result (cert-authorize-fn req)]
        (if-let [rbac-subject (get req :puppetlabs.rbac-client.middleware.authentication/rbac-subject)]
          (if (prot-rbac/is-permitted? rbac-consumer-svc rbac-subject (rbac-permission uri))
            (app req)
            (http/denied-response (i18n/tru "User does not have permission to access PuppetDB")
                                  http/status-forbidden))
          (if ssl-client-cn
            cert-auth-result
            (http/denied-response (i18n/tru "Must supply a certificate or token to access PuppetDB.")
                                  http/status-forbidden)))
        (app req)))
    app))

(defn check-rbac-status
  "Check the status of RBAC, returning a state map with an `:error`
  state if some exception is raised when asking for the status"
  [rbac-svc]
  (try
    (prot-rbac/status rbac-svc :critical)
    (catch Exception e
      (log/error e "Error getting RBAC status information")
      {:state :error})))

(defn pe-status-details
  "Create the PE version of status details that includes the status of RBAC"
  [config shared-globals-fn maint-mode-fn? rbac-consumer-svc]
  (-> (pdb-status/status-details config shared-globals-fn maint-mode-fn?)
      (assoc :rbac_status (:state (check-rbac-status rbac-consumer-svc)))))

(pls/defn-validated create-pe-status-map :- status-core/StatusCallbackResponse
  "Returns a status map containing the state of the currently running
  system (starting/running/error etc), note that RBAC being down
  currently triggers an `:unknown` error. When TK Status has a
  degraded state, we should switch to that"
  [{:keys [maintenance_mode? read_db_up? write_db_up? rbac_status]
    :as status-details}
   sync-status]
  (let [rbac-up? (= rbac_status :running)
        state (cond
                maintenance_mode? :starting
                (and read_db_up? write_db_up? rbac-up?) :running
                (and read_db_up? write_db_up? (false? rbac-up?)) :unknown
                :else :error)]
    {:state state
     :status (assoc status-details :sync_status (dash->underscore-keys sync-status))
     :alerts (sync-status/alerts sync-status)}))

(defn pe-routes [get-config get-shared-globals query sync-service]
  (map #(apply wrap-with-context %)
       (partition 2
                  ["/sync" (sync-svcs/sync-handler sync-service get-config)
                   "/ext" (pe-server/build-app query get-shared-globals)])))

(tk/defservice pe-routing-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetDBServer clean shared-globals query set-url-prefix]
   [:DefaultedConfig get-config]
   [:PuppetDBSync sync-status]
   [:PuppetDBCommandDispatcher
    enqueue-command enqueue-raw-command response-pub response-mult]
   [:MaintenanceMode
    enable-maint-mode maint-mode? disable-maint-mode]
   [:StatusService register-status]
   RbacConsumerService]
  (init [this context]
        (let [context-root (get-route this)
              query-prefix (str context-root "/query")
              config (get-config)
              {sync-config :sync
               puppetdb-config :puppetdb
               jetty-config :jetty} config
              shared-with-prefix #(assoc (shared-globals) :url-prefix query-prefix)
              rbac-consumer-svc (tksvc/get-service this :RbacConsumerService)
              sync-service (tksvc/get-service this :PuppetDBSync)]
          (set-url-prefix query-prefix)

          (turn-on-unchanged-resources!)
          (enable-corrective-change!)

          (log/info (i18n/trs "Starting PuppetDB, entering maintenance mode"))
          (add-ring-handler
           this
           (->> (pdb-app context-root
                         maint-mode?
                         (concat (reports-resources-routes shared-with-prefix)
                                 (pdb-core-routes config
                                                  shared-with-prefix
                                                  enqueue-command
                                                  query
                                                  enqueue-raw-command
                                                  response-pub
                                                  clean)
                                 (pe-routes get-config
                                            shared-with-prefix
                                            query
                                            sync-service)))
                (wrap-cert-and-token-authn (:certificate-whitelist puppetdb-config)
                                           rbac-consumer-svc)
                ;; This function is mistakenly private in the RBAC client we
                ;; should make it public and remove the `#'` here
                (#'mid-authn/wrap-token-access* rbac-consumer-svc)
                mid/wrap-with-puppetdb-middleware))

          (enable-maint-mode)
          (pdb-status/register-pdb-status register-status
                                          (fn [level]
                                            (create-pe-status-map
                                             (pe-status-details config shared-with-prefix maint-mode? rbac-consumer-svc)
                                             (sync-status))))
          context))

  (start [this context]
         (let [write-db (:scf-write-db (shared-globals))
               puppetdb-config (:puppetdb (get-config))]
           (turn-on-historical-catalogs! write-db (:historical-catalogs-limit puppetdb-config)))
         (log/info (i18n/trs "PuppetDB finished starting, disabling maintenance mode"))
         (disable-maint-mode)
         context)

  (stop [this context]
        (enable-maint-mode)
        context))
