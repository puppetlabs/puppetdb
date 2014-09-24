(ns com.puppetlabs.puppetdb.http.catalogs
  (:require [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.http :as pl-http]
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
    (pl-http/json-response (cats/canonical->wire-format version catalog))
    (pl-http/json-response {:error (str "Could not find catalog for " node)} pl-http/status-not-found)))

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
