(ns puppetlabs.puppetdb.http.catalogs
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :as middleware]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.edges :as edges]
            [puppetlabs.puppetdb.http.resources :as resources]
            [schema.core :as s]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]))

(defn catalog-status
  "Produce a response body for a request to retrieve the catalog for `node`."
  [api-version node db]
  (if-let [catalog (with-transacted-connection db
                     (c/status api-version node))]
    (http/json-response (s/validate (c/catalog-response-schema api-version) catalog))
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
      (catalog-status version node (:scf-read-db globals)))

    ;; TODO message if node does not exist

    [node "edges" &]
    (comp (edges/edges-app version) (partial http-q/restrict-query-to-node node))

    [node "resources" &]
    (comp (resources/resources-app version) (partial http-q/restrict-query-to-node node))))

(defn catalog-app
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
