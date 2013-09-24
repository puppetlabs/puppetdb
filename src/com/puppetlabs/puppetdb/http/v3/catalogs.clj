(ns com.puppetlabs.puppetdb.http.v3.catalogs
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.catalogs :as c]
            [ring.util.response :as rr])
  (:use com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [net.cgrand.moustache :only (app)]))

(defn produce-body
  "Produce a response body for a request to retrieve the catalog for `node`."
  [node db]
  (if-let [catalog (with-transacted-connection db
                     (c/catalog-for-node node))]
    (pl-http/json-response catalog)
    (pl-http/json-response {:error (str "Could not find catalog for " node)} pl-http/status-not-found)))

(def routes
  (app
    [node]
    (fn [{:keys [globals]}]
      (produce-body node (:scf-read-db globals)))))

(def catalog-app
  (-> routes
      verify-accepts-json
      (validate-no-query-params)))
