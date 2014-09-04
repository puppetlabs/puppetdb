(ns com.puppetlabs.puppetdb.http.server-time
  (:require [com.puppetlabs.puppetdb.http :as http]
            [clj-time.core :refer [now]]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-no-query-params]]))

(defn server-time-response
  [req]
  (http/json-response {:server-time (now)}))

(def routes
  (app
    [""]
    {:get server-time-response}))

(def server-time-app
  (-> routes
    verify-accepts-json
    validate-no-query-params))
