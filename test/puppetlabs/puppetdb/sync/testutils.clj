(ns puppetlabs.puppetdb.sync.testutils
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context service-id]]
            [puppetlabs.puppetdb.sync.services :refer [puppetdb-sync-service]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [compojure.core :refer [context POST routes ANY]]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-http.client :as http])
   (:import [java.net MalformedURLException URISyntaxException URL]) )

(defservice stub-server-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler]]
  (start [this tk-context]
         (if-let [handler (get-in-config [:stub-server-service :handler])]
           (add-ring-handler this (wrap-params (context "/stub" [] handler))))
         tk-context))

(defmacro with-puppetdb-instance
  "Same as the core puppetdb-instance call but adds in the sync
  service and the request-catcher/canned-response service"
  [config & body]
  `(svcs/puppetdb-instance
    ~config
    [puppetdb-sync-service stub-server-service]
    (fn [] ~@body)))

(defn sync-config
  "Returns a default TK config setup for sync testing. PuppetDB is
  hosted at /pdb, and the sync service at /sync. Takes an optional
  `stub-handler` parameter, a ring handler that will be hosted under
  '/stub'."
  ([] (sync-config nil))
  ([stub-handler]
   (-> (svcs/create-config)
       (assoc :stub-server-service {:handler stub-handler}
              :web-router-service  {:puppetlabs.puppetdb.cli.services/puppetdb-service "/pdb"
                                    :puppetlabs.puppetdb.sync.services/puppetdb-sync-service "/sync"
                                    :puppetlabs.puppetdb.sync.testutils/stub-server-service "/stub"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URL helper functions for inside a with-puppetdb-instance block
(defn pdb-url []
  (assoc svcs/*base-url* :prefix "/pdb" :version :v4))

(defn pdb-url-str []
  (base-url->str (pdb-url)))

(defn stub-url [prefix version]
  (svcs/*base-url* :prefix (str "/stub/" prefix) :version version))

(defn stub-url-str [suffix]
  (let [{:keys [protocol host port] :as base-url} svcs/*base-url*]
   (-> (URL. protocol host port (str "/stub" suffix))
       .toURI .toASCIIString)))

(defn sync-url []
  (assoc svcs/*base-url* :prefix "/sync" :version :v1))

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
