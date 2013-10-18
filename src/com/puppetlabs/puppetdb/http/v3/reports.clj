(ns com.puppetlabs.puppetdb.http.v3.reports
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.reports :as query]
            [ring.util.response :as rr]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.http :only (query-result-response)]))


(defn produce-body
  "Given an optional `query` and a database connection, return a Ring response
  with the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [query paging-options db]
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          (query/report-query->sql)
          ((partial query/query-reports paging-options))
          (query-result-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals paging-options]}]
            (produce-body
              (params "query")
              paging-options
              (:scf-read-db globals)))}))

(def reports-app
  "Ring app for querying reports"
  (-> routes
    verify-accepts-json
    (validate-query-params {:required ["query"]
                            :optional paging/query-params})
    wrap-with-paging-options))
