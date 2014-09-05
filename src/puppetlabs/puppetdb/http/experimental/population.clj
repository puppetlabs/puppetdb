(ns puppetlabs.puppetdb.http.experimental.population
  (:require [puppetlabs.puppetdb.query.population :as p]
            [puppetlabs.puppetdb.http :as http]
            [ring.util.response :as rr]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.middleware :refer :all]
            [net.cgrand.moustache :refer [app]]))

(defn get-exported-resources
  "Ring app for fetching a map from exported resource to nodes exporting and
  collecting that resource."
  [db]
  (with-transacted-connection db
    (http/json-response (p/correlate-exported-resources))))

(def routes
  (app
    ["exported-resources"]
    {:get (fn [{:keys [globals]}]
            (get-exported-resources (:scf-read-db globals)))}))

(def population-app
  (-> routes
    verify-accepts-json
    (validate-no-query-params)))
