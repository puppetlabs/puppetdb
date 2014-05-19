(ns com.puppetlabs.puppetdb.http.reports
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.reports :as query]
            [ring.util.response :as rr]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer :all]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]
            [com.puppetlabs.puppetdb.http :refer [query-result-response]]))

(defn produce-body
  "Given an optional `query` and a database connection, return a Ring response
  with the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (->> (json/parse-string query true)
           (query/report-query->sql version)
           (query/query-reports version paging-options)
           (query-result-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals paging-options]}]
           (produce-body
            version
            (params "query")
            paging-options
            (:scf-read-db globals)))}))

(defn reports-app
  "Ring app for querying reports"
  [version]
  (-> (routes version)
    verify-accepts-json
    (validate-query-params {:required ["query"]
                            :optional paging/query-params})
    wrap-with-paging-options))
