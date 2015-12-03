(ns puppetlabs.puppetdb.metrics.server
  (:require [clojure.string :as str]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [clojure.string :as str]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [puppetlabs.puppetdb.middleware :as mid]))

(def v1-app
  (app
   []
   {:get (fn [_] (http/json-response
                  (metrics/mbean-names)))}

   [& names]
   ;; Convert the given / separated mbean name from a shortened
   ;; 'commands' type to the longer form needed by the metrics beans.
   {:get (fn [_] (let [name (str/join "/" names)
                       mbean (metrics/get-mbean name)]
                   (if mbean
                     (http/json-response mbean)
                     (http/status-not-found-response "mbean" name))))}))

(def routes
  (app
   ["v1" "mbeans" &]
   {:any v1-app}))

(defn build-app
  "Generates a Ring application that handles metrics requests.
  If get-authorizer is nil or false, all requests will be accepted.
  Otherwise it must accept no arguments and return an authorize
  function that accepts a request.  The request will be allowed only
  if authorize returns :authorized.  Otherwise, the return value
  should be a message describing the reason that access was denied."
  [cert-whitelist]
  (-> routes
      mid/verify-accepts-json
      mid/validate-no-query-params
      (mid/wrap-with-puppetdb-middleware cert-whitelist)))
