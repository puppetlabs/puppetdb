(ns puppetlabs.puppetdb.meta
  (:require [clojure.tools.logging :as log]
            [net.cgrand.moustache :as moustache]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.middleware
             :refer [verify-accepts-json wrap-with-globals
                     validate-no-query-params wrap-with-puppetdb-middleware]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :as compojure]
            [puppetlabs.kitchensink.core :as kitchensink]
            [slingshot.slingshot :refer [try+]]
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
  [get-shared-globals]
  (fn [_]
    (let [{:keys [update-server product-name scf-read-db]}
          (get-shared-globals)]
      (try
        ;; If we get one of these requests from pe-puppetdb, we always want to
        ;; return 'newer->false' so that the dashboard will never try
        ;; to display info about a newer version being available.
        (if (= product-name "pe-puppetdb")
          (http/json-response {:newer false
                               :version (v/version)
                               :link nil})
          (try+
           (http/json-response (v/update-info update-server scf-read-db))
           (catch map? {m :message}
             (log/debug m (format "Could not retrieve update information (%s)"
                                  update-server))
             (http/error-response "Could not find version" 404))))

        (catch java.io.IOException e
          (log/debugf "Error when checking for latest version: %s" e)
          (http/error-response
           (format "Error when checking for latest version: %s" e)))))))

(defn version-routes
  [get-shared-globals]
  (moustache/app [""] {:get (current-version-fn (v/version))}
                 ["latest"] {:get (latest-version-fn get-shared-globals)}))

(def server-time-routes
  (moustache/app [""] {:get (fn [_] (http/json-response {:server_time (now)}))}))

(defn routes
  [get-shared-globals]
  (moustache/app ["v1" "version" &] {:any (version-routes get-shared-globals)}
                 ["v1" "server-time" &] {:any server-time-routes}))

(defn build-app
  [get-authorizer get-shared-globals]
  (-> (routes get-shared-globals)
      verify-accepts-json
      validate-no-query-params
      (wrap-with-puppetdb-middleware get-authorizer)))
