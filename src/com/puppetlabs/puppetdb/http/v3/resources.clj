(ns com.puppetlabs.puppetdb.http.v3.resources
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resources :as r]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only (verify-accepts-json validate-query-params wrap-with-paging-options)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.http :only (query-result-response)]))

(defn produce-body
  "Given a `limit`, a query, and database connection, return a Ring
  response with the query results.

  If the query can't be parsed, a 400 is returned.

  If the query would return more than `limit` results, `status-internal-error` is returned."
  [limit query paging-options db]
  {:pre [(and (integer? limit) (>= limit 0))]}
  (try
    (with-transacted-connection db
      (-> query
        (json/parse-string true)
        (r/v3-query->sql paging-options)
        ((partial r/limited-query-resources limit paging-options))
        (query-result-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options]}]
                  (produce-body
                    (:resource-query-limit globals)
                    (params "query")
                    paging-options
                    (:scf-db globals)))
            http-q/restrict-query-to-active-nodes)}))

(defn build-resources-app
  [query-app]
  (app
    []
    (verify-accepts-json query-app)

    [type title &]
    (comp query-app
          (partial http-q/restrict-resource-query-to-type type)
          (partial http-q/restrict-resource-query-to-title title))

    [type &]
    (comp query-app (partial http-q/restrict-resource-query-to-type type))))

(def resources-app
  (build-resources-app
    (-> query-app
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      (wrap-with-paging-options))))
