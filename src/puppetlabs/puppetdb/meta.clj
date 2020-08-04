(ns puppetlabs.puppetdb.meta
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.meta.version :as v]
            [puppetlabs.puppetdb.time :refer [now]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.comidi :as cmdi]
            [bidi.schema :as bidi-schema]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs tru]])
  (:import
   (clojure.lang ExceptionInfo)))

(defn current-version-fn
  "Returns a function that always returns a JSON object with the running
   version of PDB."
  [version]
  (fn [_]
    (if version
      (http/json-response {:version version})
      (http/error-response
       (tru "Could not find version") 404))))

(defn latest-version-fn
  "Returns a function returning the latest version of PuppetDB as a JSON
   object containing a `version` key with the version, as well as a `newer` key
   which is a boolean specifying whether the latest version is newer
   than the current version."
  [get-shared-globals config]
  (fn [_]
    (let [{:keys [scf-read-db]} (get-shared-globals)
          update-server (conf/update-server config)]
      (try
        ;; If we get one of these requests from pe-puppetdb, we always want to
        ;; return 'newer->false' so that the dashboard will never try
        ;; to display info about a newer version being available.
        (if (conf/pe? config)
          (http/json-response {:newer false
                               :version (v/version)
                               :link nil})
          (try
            (http/json-response (v/update-info update-server scf-read-db))
            (catch ExceptionInfo ex
              (log/debug (:message (ex-data ex))
                         (trs "Could not retrieve update information ({0})"
                              update-server))
              (http/error-response (tru "Could not find version") 404))))

        (catch java.io.IOException e
          (log/debug e (trs "Error when checking for latest version") )
          (http/error-response
           (tru "Error when checking for latest version: {0}" (.getMessage e))))))))

(pls/defn-validated meta-routes :- bidi-schema/RoutePair
  [get-shared-globals :- (s/pred fn?)
   config :- {s/Any s/Any}]
  (cmdi/context "/v1"
                (cmdi/context "/version"
                              (cmdi/ANY "" []
                                        (current-version-fn (v/version)))
                              (cmdi/ANY "/latest" []
                                        (latest-version-fn get-shared-globals config)))
                (cmdi/ANY "/server-time" []
                          (http/json-response {:server_time (now)}))))

(defn build-app
  [get-shared-globals config]
  (-> (meta-routes get-shared-globals config)
      mid/make-pdb-handler
      mid/verify-accepts-json
      mid/validate-no-query-params))
