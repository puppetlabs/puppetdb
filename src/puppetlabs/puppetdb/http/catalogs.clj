(ns puppetlabs.puppetdb.http.catalogs
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :as middleware]
            [schema.core :as s]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]))

(defn catalog-status
  "Produce a response body for a request to retrieve the catalog for `node`."
  [api-version node db]
  (if-let [catalog (with-transacted-connection db
                     (c/status api-version node))]
    (http/json-response (-> (c/catalog-response-schema api-version)
                            (s/validate catalog)
                            json/underscore-keys))
    (http/json-response {:error (str "Could not find catalog for " node)} http/status-not-found)))

(defn build-catalog-app
  [version entity]
  (fn [{:keys [params globals paging-options]}]
              (produce-streaming-body
                entity
                version
                (params "query")
                paging-options
                (:scf-read-db globals))))

(defn routes
  [version]

  (app
    [""]
    {:get (build-catalog-app version :catalogs)}

    [node]
    (fn [{:keys [globals]}]
      (catalog-status version node (:scf-read-db globals)))))

(defn catalog-app
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
