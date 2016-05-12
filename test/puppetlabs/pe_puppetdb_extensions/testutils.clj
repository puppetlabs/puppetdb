(ns puppetlabs.pe-puppetdb-extensions.testutils
  (:require [clojure.test :refer [deftest]]
            [puppetlabs.rbac-client.testutils.dummy-rbac-service :refer [dummy-rbac-service]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context service-id]]
            [puppetlabs.pe-puppetdb-extensions.config :as extconf]
            [puppetlabs.pe-puppetdb-extensions.sync.services :refer [puppetdb-sync-service]]
            [puppetlabs.pe-puppetdb-extensions.sync.pe-routing :refer [pe-routing-service]]
            [puppetlabs.puppetdb.pdb-routing :refer [pdb-routing-service]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.config :as pdbconf]
            [puppetlabs.puppetdb.testutils
             :refer [temp-dir with-alt-mq without-jmx]]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db* call-with-test-dbs]]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable notable-pdb-event?]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.cheshire :as json]
            [environ.core :refer [env]]
            [clj-http.client :as http]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid])
   (:import [java.net MalformedURLException URISyntaxException URL]) )

(defservice stub-server-service
  [[:DefaultedConfig get-config]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBSync]]
  (start [this tk-context]
         (if-let [routes (get-in (get-config) [:stub-server-service :routes])]
           (add-ring-handler this (wrap-params
                                   (mid/make-pdb-handler
                                    (cmdi/context (get-route this)
                                                  routes)))))
         tk-context))

(def pe-services
  (conj (remove #{#'pdb-routing-service #'pdbconf/config-service}
                svcs/default-services)
        #'dummy-rbac-service
        #'extconf/config-service
        #'puppetdb-sync-service
        #'stub-server-service
        #'pe-routing-service))

(defmacro with-puppetdb-instance
  "Same as the core call-with-puppetdb-instance call but adds in the
  sync service and the request-catcher/canned-response service"
  [config & body]
  `(svcs/call-with-puppetdb-instance
    ~config
    pe-services
    (fn [] ~@body)))

(def pdb-prefix "/pdb")
(def pdb-query-url-prefix (str pdb-prefix "/query"))
(def pdb-cmd-url-prefix (str pdb-prefix "/cmd"))
(def pe-pdb-url-prefix (str pdb-prefix "/ext"))
(def sync-url-prefix (str pdb-prefix "/sync"))
(def stub-url-prefix "/stub")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URL helper functions for inside a with-puppetdb-instance block
(defn pdb-query-url []
  (assoc svcs/*base-url* :prefix pdb-query-url-prefix :version :v4))

(defn pdb-query-url-str []
  (base-url->str (pdb-query-url)))

(defn pdb-cmd-url []
  (assoc svcs/*base-url* :prefix pdb-cmd-url-prefix :version :v1))

(defn pdb-cmd-url-str []
  (base-url->str (pdb-cmd-url)))

(defn pe-pdb-url []
  (assoc svcs/*base-url* :prefix pe-pdb-url-prefix :version :v1))

(defn pe-pdb-url-str []
  (base-url->str (pe-pdb-url)))

(defn stub-url [prefix version]
  (svcs/*base-url* :prefix (str stub-url-prefix "/" prefix) :version version))

(defn stub-url-str [suffix]
  (let [{:keys [protocol host port] :as base-url} svcs/*base-url*]
   (-> (URL. protocol host port (str stub-url-prefix suffix))
       .toURI .toASCIIString)))

(defn sync-url []
  (assoc svcs/*base-url* :prefix sync-url-prefix :version :v1))

(defn trigger-sync-url-str []
  (str (base-url->str (sync-url)) "/trigger-sync"))


(defn sync-config
  "Returns a default TK config setup for sync testing. PuppetDB is
  hosted at /pdb, and the sync service at /sync. Takes an optional
  `stub-routes` parameter, a ring handler that will be hosted under
  '/stub'."
  [stub-routes]
  (-> (svcs/create-temp-config)
      (assoc-in [:sync :allow-unsafe-sync-triggers] true)
      (assoc-in [:sync :allow-unsafe-cleartext-sync] true)
      (assoc :stub-server-service {:routes stub-routes}
             :web-router-service
             {:puppetlabs.pe-puppetdb-extensions.sync.pe-routing/pe-routing-service pdb-prefix
              :puppetlabs.pe-puppetdb-extensions.testutils/stub-server-service stub-url-prefix})))

(defn call-with-pdbs
  "Repeatedly calls (gen-config [previously-started-instance-info...])
  and starts a pdb instance for each returned config.  When gen-config
  returns false, calls (f instance-1-info instance-2-info...), and
  cleans up all of the instances after the call.  Suppresses the log
  unless something \"notable\" happens."
  [gen-config f]
  (letfn [(spawn-pdbs [running-instances]
            (if-let [config (gen-config running-instances)]
              (let [mq-name (str "puppetlabs.puppetdb.commands-"
                                 (inc (count running-instances)))]
                (with-alt-mq mq-name
                  (with-puppetdb-instance config
                    (spawn-pdbs (conj running-instances
                                      (let [db (-> svcs/*server*
                                                   (get-service :PuppetDBServer)
                                                   service-context
                                                   :shared-globals
                                                   :scf-write-db)]
                                        {:mq-name mq-name
                                         :config config
                                         :server svcs/*server*
                                         :db db
                                         :query-fn (partial cli-svcs/query
                                                            (get-service
                                                             svcs/*server*
                                                             :PuppetDBServer))
                                         :server-url svcs/*base-url*
                                         :query-url (pdb-query-url)
                                         :command-url (pdb-cmd-url)
                                         :sync-url (sync-url)}))))))
              (apply f running-instances)))]
    (with-log-suppressed-unless-notable notable-pdb-event?
      (without-jmx
       (spawn-pdbs [])))))

(defn call-with-related-ext-instances
  "Creates a PuppetDB extensions instance for each config in configs,
  and then calls (f instance-1-info instance-2-info ...).  Each
  instance will have a clean test database provided by with-test-dbs,
  and if a config-filter is provided, (config-filter config
  running-instances-info) will be called before starting each instance, and
  must return the desired config for the instance under construction."
  [configs config-filter f]
  (call-with-test-dbs
   (count configs)
   (fn [& dbs]
     (call-with-pdbs
      (fn [running-instances]
        (let [i (count running-instances)]
          (when (< i (count configs))
            (let [cfg (assoc (configs i)
                             :database (merge (nth dbs i)
                                              (:database (configs i))))]
              (if config-filter
                (config-filter cfg running-instances)
                cfg)))))
      f))))

(defmacro with-related-ext-instances
  "instance-bindings => [name config ...]

  Evaluates body with a PuppetDB extensions instance for each config
  bound to the corresponding name.  Each instance will have a clean
  test database provided by with-test-dbs, and if a config-filter is
  provided, then (config-filter config
  previously-started-instances-info) will be called before starting
  each instance, and must return the desired config for the instance
  under construction."
  [instance-bindings config-filter & body]
  (let [names (take-nth 2 instance-bindings)]
    (assert (even? (count instance-bindings)))
    `(let [configs# [~@(take-nth 2 (rest instance-bindings))]]
       (call-with-related-ext-instances configs# ~config-filter
         (fn [~@names]
           ~@body)))))

(defmacro with-ext-instances
  "instance-bindings => [name config ...]

  Evaluates body with a PuppetDB extensions instance for each config
  bound to each name.  Each instance will have a clean test database
  provided by with-test-dbs."
  [instance-bindings & body]
  `(with-related-ext-instances ~instance-bindings nil ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General utility functions
(defn index-by [key s]
  (into {} (for [val s] [(key val) val])))

(defn json-request [body]
  {:headers {"content-type" "application/json"}
   :throw-entire-message true
   :body (json/generate-string body)})

(defn json-response [m]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string m)})

(defn get-response
  [base-url suffix opts]
  (http/get (str (base-url->str base-url) suffix) opts))

;; alias to a different name because 'sync' means 'synchronous' here, and that's REALLY confusing.
(def blocking-command-post svcs/sync-command-post)
