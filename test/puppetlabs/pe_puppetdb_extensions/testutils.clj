(ns puppetlabs.pe-puppetdb-extensions.testutils
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context service-id]]
            [puppetlabs.pe-puppetdb-extensions.sync.services :refer [puppetdb-sync-service]]
            [puppetlabs.pe-puppetdb-extensions.server :refer [pe-puppetdb-service]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [compojure.core :refer [context POST routes ANY]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-http.client :as http])
   (:import [java.net MalformedURLException URISyntaxException URL]) )

(defservice stub-server-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler get-route]]
  (start [this tk-context]
         (if-let [handler (get-in-config [:stub-server-service :handler])]
           (add-ring-handler this (wrap-params (context (get-route this) [] handler))))
         tk-context))

(defmacro with-puppetdb-instance
  "Same as the core call-with-puppetdb-instance call but adds in the
  sync service and the request-catcher/canned-response service"
  [config & body]
  `(svcs/call-with-puppetdb-instance
    ~config
    [puppetdb-sync-service pe-puppetdb-service stub-server-service]
    (fn [] ~@body)))

(def pdb-query-url-prefix "/pdb/query")
(def pdb-cmd-url-prefix "/pdb/cmd")
(def pe-pdb-url-prefix "/pdb/ext")
(def sync-url-prefix "/pdb/sync")
(def stub-url-prefix "/stub")

(defn sync-config
  "Returns a default TK config setup for sync testing. PuppetDB is
  hosted at /pdb, and the sync service at /sync. Takes an optional
  `stub-handler` parameter, a ring handler that will be hosted under
  '/stub'."
  ([] (sync-config nil))
  ([stub-handler]
   (-> (svcs/create-config)
       (assoc-in [:sync :allow-unsafe-sync-triggers] true)
       (assoc :stub-server-service {:handler stub-handler}
              :web-router-service  {:puppetlabs.pe-puppetdb-extensions.server/pe-puppetdb-service pe-pdb-url-prefix
                                    :puppetlabs.pe-puppetdb-extensions.sync.services/puppetdb-sync-service sync-url-prefix
                                    :puppetlabs.pe-puppetdb-extensions.testutils/stub-server-service stub-url-prefix}))))

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

(defn get-json [base-url suffix & [opts]]
  (let [opts (or opts {})]
    (-> (str (base-url->str base-url) suffix)
        (http/get opts)
        :body
        (json/parse-string true))))

(defn get-response
  [base-url suffix opts]
  (http/get (str (base-url->str base-url) suffix) opts))

;; alias to a different name because 'sync' means 'synchronous' here, and that's REALLY confusing.
(def blocking-command-post svcs/sync-command-post)
