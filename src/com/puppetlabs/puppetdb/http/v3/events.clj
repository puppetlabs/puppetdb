(ns com.puppetlabs.puppetdb.http.v3.events
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.utils :as pl-utils]
            [com.puppetlabs.puppetdb.query.events :as query]
            [cheshire.core :as json]
            [ring.util.response :as rr]
            [com.puppetlabs.puppetdb.query.paging :as paging])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.http :only (parse-boolean-query-param)]
        [com.puppetlabs.puppetdb.http :only (query-result-response)]))

(defn produce-body
  "Given a `limit`, a query and a database connection, return a Ring response
  with the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned.

  If the query would return more than `limit` results, `status-internal-error`
  is returned."
  [limit query query-options paging-options db]
  {:pre [(and (integer? limit) (>= limit 0))]}
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          ((partial query/query->sql query-options))
          ((partial query/limited-query-resource-events limit paging-options))
          (query-result-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(defn get-query-options
  "Given a map of query params, build up a list of the legal query options
  support by the event counts query."
  [params]
  ;; For now, the only supported query option is `:distinct-resources?`
  {:distinct-resources? (parse-boolean-query-param params "distinct-resources")})

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals paging-options]}]
            (let [limit         (:event-query-limit globals)
                  query-options (get-query-options params)]
              (produce-body
                limit
                (params "query")
                query-options
                paging-options
                (:scf-db globals))))}))

(def events-app
  "Ring app for querying events"
  (-> routes
    verify-accepts-json
    (validate-query-params {:required ["query"]
                            :optional (cons "distinct-resources" paging/query-params)})
    wrap-with-paging-options))
