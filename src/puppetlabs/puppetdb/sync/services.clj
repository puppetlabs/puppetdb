(ns puppetlabs.puppetdb.sync.services
  (:require [compojure.core :as compojure]
            [puppetlabs.puppetdb.sync.command :as command]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :refer [routes POST context]]
            [puppetlabs.puppetdb.utils :as utils]))

(defn sync-command?
  "True if this is a sync command"
  [x]
  (= (:command x) "sync"))

(defn app
  "Top level route for PuppetDB sync"
  [query-fn origin-path]
  (compojure/context "/sync" []
                     (routes
                      (POST "/v1/trigger-sync" {:keys [body]}
                            (let [{:strs [remote_host_path]} (json/parse-string (slurp body))]
                              (command/sync-to-remote query-fn origin-path remote_host_path)
                              {:status 200 :body "success"})))))

(defn sync-listener
  "Takes a `query-fn` for in-process querying this instance
  and `submit-command-fn` for in-process submitting of commands and
  returns a function that accepts sync commands"
  [query-fn submit-command-fn]
  (fn [{:keys [payload version]}]
    (command/sync-from-remote query-fn submit-command-fn payload)))

(defservice puppetdb-sync-service
  [[:ConfigService get-in-config get-config]
   [:WebroutingService add-ring-handler]
   [:MessageListenerService register-listener]
   [:PuppetDBServer query shared-globals submit-command]]
  (start [this context]
         (let [base-url {:protocol "http"
                         :host "localhost"
                         :port (get-in-config [:jetty :port])
                         :prefix (get-in-config [:web-router-service :puppetlabs.puppetdb.cli.services/puppetdb-service])}]
           (add-ring-handler this (app query (utils/base-url->str base-url)))
           (register-listener sync-command? (sync-listener query submit-command))
           context)))
