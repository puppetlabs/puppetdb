(ns com.puppetlabs.puppetdb.http.v1.version
  (:require [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr]
            [clojure.tools.logging :as log])
  (:use [com.puppetlabs.puppetdb.version :only [version update-info]]
        com.puppetlabs.middleware
        [net.cgrand.moustache :only [app]]))

(defn current-version-response
  "Responds with the current version of PuppetDB as a JSON object containing a
  `version` key."
  [req]
  (if-let [version (version)]
    (pl-http/json-response {:version version})
    (pl-http/error-response "Could not find version" 404)))

(defn latest-version-response
  "Responds with the latest version of PuppetDB as a JSON object containing a
  `version` key with the version, as well as a `newer` key which is a boolean
  specifying whether the latest version is newer than the current version."
  [{:keys [globals]}]
  {:pre [(:update-server globals)]}
  (let [update-server (:update-server globals)]
    (try
      (if-let [update (update-info update-server (:scf-db globals))]
        (pl-http/json-response update)
        (do
          (log/debug (format
                       "Unable to determine latest version via update-server: '%s'"
                       update-server))
          (pl-http/error-response "Could not find version" 404)))
      (catch java.io.IOException e
        (log/debug (format "Error when checking for latest version: %s" e))
        (pl-http/error-response
          (format "Error when checking for latest version: %s" e))))))

(def routes
  (app
    [""]
    {:get current-version-response}

    ["latest"]
    {:get latest-version-response}))

(def version-app
  "A moustache app for retrieving current and latest version information."
  (verify-accepts-json routes))
