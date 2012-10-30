;; ## Request Format
;; TODO: fill this in once it stabilizes

(ns com.puppetlabs.puppetdb.http.v2.report
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.report :as query]
            [ring.util.response :as rr]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))


(defn produce-body
  "Given an optional `query` and a database connection, return a Ring response
  with the query results.  The result format conforms to that documented above.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [query db]
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          (query/report-query->sql)
          (query/query-reports)
          (pl-http/json-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
            (produce-body
              (params "query")
              (:scf-db globals)))}))

(def reports-app
  "Ring app for querying reports"
  (-> routes
    verify-accepts-json
    (verify-param-exists "query")))
