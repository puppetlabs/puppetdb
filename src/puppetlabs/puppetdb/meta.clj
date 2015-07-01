(ns puppetlabs.puppetdb.meta
  (:require [clojure.tools.logging :as log]
            [net.cgrand.moustache :as moustache]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.middleware
             :refer [verify-accepts-json validate-no-query-params wrap-with-puppetdb-middleware]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :as compojure]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.meta.version :as v]))

(defn current-version-fn
  "Returns a function that always returns a JSON object with the running
   version of PDB."
  [version]
  (fn [_]
    (if version
      (http/json-response {:version version})
      (http/error-response "Could not find version" 404))))

(defn latest-version-fn
  "Returns a function returning the latest version of PuppetDB as a JSON
   object containing a `version` key with the version, as well as a `newer` key
   which is a boolean specifying whether the latest version is newer
   than the current version."
  [{:keys [update-server product-name scf-read-db]}]
  {:pre [(and update-server product-name scf-read-db)]}
  (fn [_]
    (try
      ;; if we get one of these requests from
      ;; pe-puppetdb, we always want to
      ;; return 'newer->false' so that the
      ;; dashboard will never try to
      ;; display info about a newer version
      ;; being available
      (if (= product-name "pe-puppetdb")
        (http/json-response {:newer false :version (v/version) :link nil})
        (if-let [result (v/update-info update-server scf-read-db)]
          (http/json-response result)
          (do (log/debugf "Unable to determine latest version via update-server: '%s'" update-server)
              (http/error-response "Could not find version" 404))))

      (catch java.io.IOException e
        (log/debugf "Error when checking for latest version: %s" e)
        (http/error-response
          (format "Error when checking for latest version: %s" e))))))

(defn version-routes
  [globals]
  (moustache/app [""] {:get (current-version-fn (v/version))}
                 ["latest"] {:get (latest-version-fn globals)}))

(def server-time-routes
  (moustache/app [""] {:get (fn [_] (http/json-response {:server_time (now)}))}))

(defn routes
  [globals]
  (moustache/app ["v1" "version" &] {:any (version-routes globals)}
                 ["v1" "server-time" &] {:any server-time-routes}))

(defn build-app
  [{:keys [authorizer] :as globals}]
  (-> (routes globals)
      verify-accepts-json
      validate-no-query-params
      (wrap-with-puppetdb-middleware authorizer)))

(defservice metadata-service
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]]

  (start [this context]
         (log/info "Starting metadata service")
         (->> (build-app (shared-globals))
              (compojure/context (get-route this) [])
              (add-ring-handler this))
         context))
