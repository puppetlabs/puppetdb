(ns com.puppetlabs.puppetdb.http.catalogs
  (:require [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.puppetdb.query.catalogs :as c]
            [com.puppetlabs.puppetdb.catalogs :as cats]
            [com.puppetlabs.middleware :as middleware]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]))

(defn produce-body
  "Produce a response body for a request to retrieve the catalog for `node`."
  [version node db]
  (if-let [catalog (with-transacted-connection db
                     (c/catalog-for-node version node))]
    (http/json-response (cats/canonical->wire-format version catalog))
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
