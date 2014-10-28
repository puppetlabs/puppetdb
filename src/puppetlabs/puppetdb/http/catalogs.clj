(ns puppetlabs.puppetdb.http.catalogs
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.catalogs :as cats]
            [puppetlabs.puppetdb.middleware :as middleware]
            [schema.core :as s]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]))

(defn produce-body
  "Produce a response body for a request to retrieve the catalog for `node`."
  [api-version node db]
  (if-let [catalog (with-transacted-connection db
                     (c/catalog-for-node api-version node))]
    (http/json-response (s/validate (c/catalog-response-schema api-version) catalog))
    (http/json-response {:error (str "Could not find catalog for " node)} http/status-not-found)))

(defn routes
  [version]
  (app
   [node]
   (fn [{:keys [globals]}]
     (produce-body version node (:scf-read-db globals)))))

(defn catalog-app
  [version]
  (-> (routes version)
      middleware/verify-accepts-json
      (middleware/validate-no-query-params)))
