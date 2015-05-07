(ns com.puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.http.events :as events-http]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params]]
            [clojure.tools.logging :as log]
            [net.cgrand.moustache :refer [app]]))

(defn produce-body
  "Given a database connection, a query, a value to summarize by, and optionally
  a query to filter the counts and a value to count by, return a Ring response
  with the the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [version {:strs [query summarize-by counts-filter count-by] :as query-params} db]
  {:pre [(string? query)
         (string? summarize-by)
         ((some-fn nil? string?) counts-filter)
         ((some-fn nil? string?) count-by)]}
  (try
    (let [query               (json/parse-strict-string query true)
          counts-filter       (if counts-filter (json/parse-string counts-filter true))
          distinct-options    (events-http/validate-distinct-options! query-params)]
      (with-transacted-connection db
        (-> (aggregate-event-counts/query->sql version query summarize-by
              (merge {:counts-filter counts-filter :count-by count-by}
                     distinct-options))
            (aggregate-event-counts/query-aggregate-event-counts)
            (pl-http/json-response))))
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
    {:get (fn [{:keys [params globals]}]
            (produce-body version params (:scf-read-db globals)))}))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version]
  (log/warn "The aggregate-event-counts endpoint is experimental and may be altered or removed in the future.")
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional ["counts-filter" "count-by" "distinct-resources"
                                         "distinct-start-time" "distinct-end-time"]})))
