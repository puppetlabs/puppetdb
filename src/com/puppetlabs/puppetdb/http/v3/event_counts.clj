(ns com.puppetlabs.puppetdb.http.v3.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event-counts :as event-counts]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging])
  (:use     [com.puppetlabs.jdbc :only (with-transacted-connection)]
            [com.puppetlabs.middleware :only [verify-accepts-json validate-query-params wrap-with-paging-options]]
            [net.cgrand.moustache :only [app]]
            [com.puppetlabs.http :only [parse-boolean-query-param]]
            [com.puppetlabs.puppetdb.http :only (query-result-response)]))

(defn produce-body
  "Given a database connection, a query, a value to summarize by, and optionally
  a query to filter the counts and a value to count by, return a Ring response
  with the the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [{:strs [query summarize-by counts-filter count-by] :as query-params} paging-options db]
  {:pre [(string? query)
         (string? summarize-by)
         ((some-fn nil? string?) counts-filter)
         ((some-fn nil? string?) count-by)]}
  (try
    (let [query               (json/parse-string query true)
          counts-filter       (if counts-filter (json/parse-string counts-filter true))
          distinct-resources? (parse-boolean-query-param query-params "distinct-resources")]
      (with-transacted-connection db
        (-> query
            (event-counts/query->sql summarize-by
              {:counts-filter counts-filter :count-by count-by
               :distinct-resources? distinct-resources?})
            ((partial event-counts/query-event-counts paging-options summarize-by))
            (query-result-response))))
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
            (produce-body params paging-options (:scf-db globals)))}))

(def event-counts-app
  "Ring app for querying for summary information about resource events."
  (-> routes
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional (concat ["counts-filter" "count-by" "distinct-resources"]
                                          paging/query-params) })
      wrap-with-paging-options))
