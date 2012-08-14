(ns com.puppetlabs.puppetdb.http.version
  (:require [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.utils :only [version upgrade-info]]
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
  [req]
  (if-let [upgrade (try
                     (upgrade-info)
                     (catch java.io.IOException e))]
    (pl-http/json-response upgrade)
    (pl-http/error-response "Could not find version" 404)))

(def version-app
  (-> (app
        [""]
        {:get current-version-response}

        ["latest"]
        {:get latest-version-response})
    (pl-http/must-accept-type "application/json")))
