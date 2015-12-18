(ns puppetlabs.puppetdb.http.fact-names
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [puppetlabs.puppetdb.middleware :refer [validate-query-params]]))

(defn query-route
  "Returns a route for querying PuppetDB that supports the standard
   paging parameters. Also accepts GETs and POSTs. Composes
   `optional-handlers` with the middleware function that executes the
   query."
  [version param-spec & optional-handlers]
  (app
   (http-q/extract-query param-spec)
   (apply comp
          (fn [{:keys [params globals puppetdb-query]}]
            (let [puppetdb-query (assoc-when puppetdb-query :order_by [[:name :ascending]])]
              (produce-streaming-body
               version
               (http-q/validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
               (select-keys globals [:scf-read-db :url-prefix :pretty-print :warn-experimental]))))
          (partial http-q/restrict-query-to-entity "fact_names")
          optional-handlers)))

(defn fact-names-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    (app
     []
     (query-route version param-spec identity))))
