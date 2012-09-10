(ns com.puppetlabs.puppetdb.http.catalog
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.catalog :as c]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Produce a response body for a request to retrieve the catalog for `node`."
  [node db]
  (let [catalog (with-transacted-connection db
                  (c/catalog-for-node node))]
    (if catalog
      (pl-http/json-response catalog)
      (pl-http/json-response {:error (str "Could not find catalog for " node)} pl-http/status-not-found))))

(defn catalog-app
  "Ring app for retrieving catalogs"
  [{:keys [params headers globals] :as request}]
  (let [node (params "node")]
    (cond
     (empty? node)
     (rr/status (rr/response "missing node")
                pl-http/status-bad-request)

     (not (pl-http/acceptable-content-type
           "application/json"
           (headers "accept")))
     (rr/response "must accept application/json")

     :else
     (produce-body node (:scf-db globals)))))
