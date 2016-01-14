(ns puppetlabs.puppetdb.metrics.server
  (:require [clojure.string :as str]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [puppetlabs.puppetdb.middleware :as mid]
            [bidi.schema :as bidi-schema]
            [schema.core :as s]))

(s/def ^:always-validate routes :- bidi-schema/RoutePair
  (cmdi/context "/v1/mbeans"
                (cmdi/GET "" []
                          (fn [req]
                            (http/json-response
                             (metrics/mbean-names))))
                (cmdi/GET ["/" [#".*" :names]] []
                          (fn [{:keys [route-params] :as req}]
                            (let [name (java.net.URLDecoder/decode (:names route-params))
                                  mbean (metrics/get-mbean name)]
                              (if mbean
                                (http/json-response mbean)
                                (http/status-not-found-response "mbean" name)))))))

(defn build-app
  "Generates a Ring application that handles metrics requests.
  If get-authorizer is nil or false, all requests will be accepted.
  Otherwise it must accept no arguments and return an authorize
  function that accepts a request.  The request will be allowed only
  if authorize returns :authorized.  Otherwise, the return value
  should be a message describing the reason that access was denied."
  [cert-whitelist]
  (-> routes
      mid/make-pdb-handler 
      mid/verify-accepts-json
      mid/validate-no-query-params
      (mid/wrap-with-puppetdb-middleware cert-whitelist)))

