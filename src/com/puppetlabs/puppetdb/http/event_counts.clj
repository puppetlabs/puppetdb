(ns com.puppetlabs.puppetdb.http.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event-counts :as event-counts]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.http.events :as events-http]
            [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.puppetdb.query :as query]))

(defn produce-body
  "Given a query, options and a database connection, return a Ring response with the
  query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [version {:strs [query summarize-by counts-filter count-by] :as query-params} paging-options db]
  {:pre [(string? query)
         (string? summarize-by)
         ((some-fn nil? string?) counts-filter)
         ((some-fn nil? string?) count-by)]}
  (try
    (let [query               (json/parse-strict-string query true)
          counts-filter       (if counts-filter (json/parse-string counts-filter true))
          distinct-options    (events-http/validate-distinct-options! query-params)]
      (jdbc/with-transacted-connection db
        (let [{[sql & params] :results-query
               count-query    :count-query} (event-counts/query->sql version query summarize-by
                                                                     (merge {:counts-filter counts-filter
                                                                             :count-by count-by}
                                                                            distinct-options)
                                                                     paging-options)
               resp (pl-http/stream-json-response
                     (fn [f]
                       (jdbc/with-transacted-connection db
                         (query/streamed-query-result version sql params
                                                      (comp f (event-counts/munge-result-rows summarize-by))))))]
          (if count-query
            (http/add-headers resp {:count (jdbc/get-result-count count-query)})
            resp))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(defn routes
  [version]
  (app
    [""]
    {:get (fn [{:keys [params globals paging-options]}]
            (produce-body
             version
             params
             paging-options
             (:scf-read-db globals)))}))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version]
  (log/warn "The event-counts endpoint is experimental and may be altered or removed in the future.")
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional (concat ["counts-filter" "count-by"
                                                 "distinct-resources" "distinct-start-time"
                                                 "distinct-end-time"]
                                          paging/query-params)})
      wrap-with-paging-options))
