(ns puppetlabs.puppetdb.http.server-time
  (:require [puppetlabs.puppetdb.http :as http]
            [clj-time.core :refer [now]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-no-query-params]]))

(defn server-time-response
  [req]
  (http/json-response {:server_time (now)}))

(def routes
  (app
   [""]
   {:get server-time-response}))

(def server-time-app
  (-> routes
      verify-accepts-json
      validate-no-query-params))
