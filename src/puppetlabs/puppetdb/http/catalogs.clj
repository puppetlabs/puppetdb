(ns puppetlabs.puppetdb.http.catalogs
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query-eng :as eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :as middleware]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.edges :as edges]
            [puppetlabs.puppetdb.http.resources :as resources]
            [schema.core :as s]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options wrap-with-parent-check]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]))

(defn catalog-status
  "Produce a response body for a request to retrieve the catalog for `node`."
  [api-version node db url-prefix]
  (let [catalog (first
                 (eng/stream-query-result :catalogs
                                          api-version
                                          ["=" "certname" node]
                                          {}
                                          db
                                          url-prefix))]
    (if catalog
      (http/json-response (s/validate catalogs/catalog-query-schema catalog))
      (http/status-not-found-response "catalog" node))))

(defn build-catalog-app
  [{:keys [scf-read-db url-prefix api-version]} entity]
  (comp (fn [{:keys [params paging-options]}]
          (produce-streaming-body
           entity
           api-version
           (params "query")
           paging-options
           scf-read-db
           url-prefix))
        http-q/restrict-query-to-active-nodes))

(defn routes
  [{:keys [api-version url-prefix scf-read-db] :as globals}]

  (app
    [""]
    {:get (build-catalog-app globals :catalogs)}

    [node]
    (constantly
     (catalog-status api-version node scf-read-db url-prefix))

    [node "edges" &]
    (-> (comp (edges/edges-app globals false) (partial http-q/restrict-query-to-node node))
        (wrap-with-parent-check globals :catalog node))

    [node "resources" &]
    (-> (comp (resources/resources-app globals false) (partial http-q/restrict-query-to-node node))
        (wrap-with-parent-check globals :catalog node))))

(defn catalog-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      (validate-query-params {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
