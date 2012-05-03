(ns com.puppetlabs.puppetdb.http.catalog
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.query.catalog :as c]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Produce a response body for a request to retrieve the catalog for `node`."
  [node db]
  (let [catalog (with-transacted-connection db
                  (c/catalog-for-node node))]
    (if catalog
      (utils/json-response catalog)
      (utils/json-response {:error (str "Could not find catalog for " node)} 404))))

(defn catalog-app
  "Ring app for retrieving catalogs"
  [{:keys [params headers globals] :as request}]
  (let [node (params "node")]
    (cond
     (empty? node)
     (-> (rr/response "missing node")
         (rr/status 400))

     (not (utils/acceptable-content-type
           "application/json"
           (headers "accept")))
     (-> (rr/response "must accept application/json"))

     :else
     (produce-body node (:scf-db globals)))))
