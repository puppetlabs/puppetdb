(ns com.puppetlabs.puppetdb.http.experimental.population
  (:require [com.puppetlabs.puppetdb.query.population :as p]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [net.cgrand.moustache :only [app]]))

(defn get-exported-resources
  "Ring app for fetching a map from exported resource to nodes exporting and
  collecting that resource."
  [{:keys [params headers globals] :as request}]
  (cond
   (not (pl-http/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json"))

   :else
   (with-transacted-connection (:scf-db globals)
     (pl-http/json-response (p/correlate-exported-resources)))))

(def population-app
  (app
   ["exported-resources"]
   {:get get-exported-resources}))
