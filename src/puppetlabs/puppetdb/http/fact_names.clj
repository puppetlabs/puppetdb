(ns puppetlabs.puppetdb.http.fact-names
  (:require [puppetlabs.puppetdb.query.facts :as f]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body']]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.http :refer [query-result-response]]))

(defn query-route
  "Returns a route for querying PuppetDB that supports the standard
   paging parameters. Also accepts GETs and POSTs. Composes
   `optional-handlers` with the middleware function that executes the
   query."
  [entity version & optional-handlers]
  (app
    http-q/extract-query
    (apply comp
           (fn [{:keys [params globals puppetdb-query]}]
             (let [puppetdb-query (if (nil? (:order_by puppetdb-query))
                                    (assoc puppetdb-query :order_by [[:name :ascending]])
                                    puppetdb-query)]
               (produce-streaming-body'
                 entity
                 version
                 (http-q/validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                 (:scf-read-db globals)
                 (:url-prefix globals))))
           optional-handlers)))

(defn routes
  [version]
  (app
    []
    (query-route :fact-names version identity)))

(defn fact-names-app
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
