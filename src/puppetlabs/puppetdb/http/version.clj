(ns puppetlabs.puppetdb.http.version
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.version :as v]
            [puppetlabs.puppetdb.middleware :as mid]))

(defn current-version-response
  "Responds with the current version of PuppetDB as a JSON object containing a
  `version` key."
  [_]
  (if-let [version (v/version)]
    (http/json-response {:version version})
    (http/error-response "Could not find version" 404)))

(defn latest-version-response
  "Responds with the latest version of PuppetDB as a JSON object containing a
  `version` key with the version, as well as a `newer` key which is a boolean
  specifying whether the latest version is newer than the current version."
  [{:keys [globals] :as request}]
  {:pre [(:update-server globals)
         (:product-name  globals)]}
  (let [update-server (:update-server globals)
        product-name  (:product-name globals)]
    (try
      (kitchensink/cond-let [result]
                            ;; if we get one of these requests from pe-puppetdb, we always want to
                            ;; return 'newer->false' so that the dashboard will never try to
                            ;; display info about a newer version being available
                            (= product-name "pe-puppetdb")
                            (http/json-response {"newer"   false
                                                 "version" (v/version)
                                                 "link"    nil})

                            (v/update-info update-server (:scf-read-db globals))
                            (http/json-response result)

                            :else
                            (do
                              (log/debugf "Unable to determine latest version via update-server: '%s'"
                                          update-server)
                              (http/error-response "Could not find version" 404)))

      (catch java.io.IOException e
        (log/debugf "Error when checking for latest version: %s" e)
        (http/error-response
         (format "Error when checking for latest version: %s" e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(def current-version
  "Function for validating the request then getting the current (running) version PuppetDB"
  (-> current-version-response
      mid/verify-accepts-json
      mid/validate-no-query-params))

(def latest-version
  "Function for validating the request, then getting latest version of PuppetDB"
  (-> latest-version-response
      mid/verify-accepts-json
      mid/validate-no-query-params))
