(ns puppetlabs.puppetdb.http.catalogs
  (:require [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.edges :as edges]
            [puppetlabs.puppetdb.http.resources :as resources]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [schema.core :as s]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-parent-check]]
            [net.cgrand.moustache :refer [app]]))

(defn catalog-status
  "Produce a response body for a request to retrieve the catalog for `node`."
  [api-version node options]
  (let [catalog (first
                 (eng/stream-query-result api-version
                                          ["from" "catalogs" ["=" "certname" node]]
                                          {}
                                          options))]
    (if catalog
      (http/json-response (s/validate catalogs/catalog-query-schema
                                      (kitchensink/mapvals sutils/parse-db-json [:edges :resources] catalog)))
      (http/status-not-found-response "catalog" node))))

(defn catalog-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params}]
    (app
     []
     (http-q/query-route-from "catalogs" version param-spec optional-handlers)

     [node]
     (fn [{:keys [globals]}]
       (catalog-status version node
                       (select-keys globals [:scf-read-db :url-prefix :warn-experimental])))

     [node "edges" &]
     (-> (edges/edges-app version false (partial http-q/restrict-query-to-node node))
         (wrap-with-parent-check version :catalog node))

     [node "resources" &]
     (-> (resources/resources-app version false (partial http-q/restrict-query-to-node node))
         (wrap-with-parent-check version :catalog node)))))
