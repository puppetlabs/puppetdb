(ns com.puppetlabs.puppetdb.http.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event-counts :as event-counts]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.http.events :as events-http]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.http :refer [parse-boolean-query-param]]
            [com.puppetlabs.puppetdb.http :refer [query-result-response]]))

(defn produce-body
  "Given a database connection, a query, a value to summarize by, and optionally
  a query to filter the counts and a value to count by, return a Ring response
  with the the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [version {:strs [query summarize-by counts-filter count-by] :as query-params} paging-options db]
  {:pre [(string? query)
         (string? summarize-by)
         ((some-fn nil? string?) counts-filter)
         ((some-fn nil? string?) count-by)]}
  (try
    (let [query               (json/parse-string query true)
          counts-filter       (if counts-filter (json/parse-string counts-filter true))
          distinct-options    (events-http/validate-distinct-options! query-params)]
      (with-transacted-connection db
        (-> (event-counts/query->sql version query summarize-by
              (merge {:counts-filter counts-filter :count-by count-by}
                     distinct-options))
            ((partial event-counts/query-event-counts paging-options summarize-by))
            (query-result-response))))
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
            (produce-body version params paging-options (:scf-read-db globals)))}))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional (concat ["counts-filter" "count-by"
                                                 "distinct-resources" "distinct-start-time"
                                                 "distinct-end-time"]
                                          paging/query-params) })
      wrap-with-paging-options))
