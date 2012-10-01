(ns com.puppetlabs.puppetdb.http.experimental.population
  (:require [com.puppetlabs.puppetdb.query.population :as p]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]
        com.puppetlabs.middleware
        [net.cgrand.moustache :only [app]]))

(defn get-exported-resources
  "Ring app for fetching a map from exported resource to nodes exporting and
  collecting that resource."
  [db]
  (with-transacted-connection db
    (pl-http/json-response (p/correlate-exported-resources))))

(def routes
  (app
    ["exported-resources"]
    {:get (fn [{:keys [globals]}]
            (get-exported-resources (:scf-db globals)))}))

(def population-app
  (verify-accepts-json routes))
