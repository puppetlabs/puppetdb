(ns com.puppetlabs.puppetdb.http.version
  (:require [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.utils :only [version]]))

(defn version-app
  [{:keys [headers]}]
  (cond
    (not (pl-http/acceptable-content-type
           "application/json"
           (headers "accept")))
    (-> (rr/response "must accept application/json")
        (rr/status pl-http/status-not-acceptable))
    :else
    (pl-http/json-response {:version (version)})))
