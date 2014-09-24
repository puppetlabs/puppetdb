(ns com.puppetlabs.puppetdb.http.server-time
  (:require [com.puppetlabs.http :as pl-http])
  (:use [clj-time.core :only [now]]
        [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only [verify-accepts-json validate-no-query-params]]))

(defn server-time-response
  [req]
  (pl-http/json-response {:server-time (now)}))

(def routes
  (app
   [""]
   {:get server-time-response}))

(def server-time-app
  (-> routes
      verify-accepts-json
      validate-no-query-params))
