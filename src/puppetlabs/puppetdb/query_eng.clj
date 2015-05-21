(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.catalogs :as catalogs]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.query.environments :as environments]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.factsets :as factsets]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.nodes :as nodes]
            [puppetlabs.puppetdb.query.reports :as reports]
            [puppetlabs.puppetdb.query.report-data :as report-data]
            [puppetlabs.puppetdb.query.resources :as resources]))

(defn entity->sql-fns
  [entity version paging-options url-prefix]
  (let [[query->sql munge-fn]
        (case entity
          :aggregate-event-counts [aggregate-event-counts/query->sql aggregate-event-counts/munge-result-rows]
          :event-counts [event-counts/query->sql (event-counts/munge-result-rows (first paging-options))]
          :facts [facts/query->sql facts/munge-result-rows]
          :fact-contents [fact-contents/query->sql fact-contents/munge-result-rows]
          :fact-paths [facts/fact-paths-query->sql facts/munge-path-result-rows]
          :factsets [factsets/query->sql factsets/munge-result-rows]
          :catalogs [catalogs/query->sql catalogs/munge-result-rows]
          :nodes [nodes/query->sql (constantly identity)]
          :environments [environments/query->sql (constantly identity)]
          :events [events/query->sql events/munge-result-rows]
          :edges [edges/query->sql edges/munge-result-rows]
          :reports [reports/query->sql reports/munge-result-rows]
          :report-metrics [report-data/metrics-query->sql (report-data/munge-result-rows :metrics)]
          :report-logs [report-data/logs-query->sql (report-data/munge-result-rows :logs)]
          :resources [resources/query->sql resources/munge-result-rows])]
    [#(query->sql version % paging-options) (munge-fn version url-prefix)]))

(defn stream-query-result
  "Given a query, and database connection, return a Ring response with the query
  results."
  [entity version query paging-options db url-prefix row-fn]
  (let [[query->sql munge-fn] (entity->sql-fns entity version paging-options url-prefix)]
    (jdbc/with-transacted-connection db
      (let [{:keys [results-query]} (query->sql query)]
        (->> (jdbc/with-query-results-cursor results-query)
             munge-fn
             row-fn)))))

(defn produce-streaming-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [entity version query paging-options db url-prefix]
  (try
    (jdbc/with-transacted-connection db
      (let [[query->sql munge-fn] (entity->sql-fns entity version paging-options url-prefix)
            {:keys [results-query count-query]} (-> query (json/parse-strict-string true) query->sql)
            resp (http/streamed-response
                  buffer
                  (jdbc/with-transacted-connection db
                    (-> (jdbc/with-query-results-cursor results-query)
                        munge-fn
                        (json/generate-pretty-stream buffer))))]
        (cond-> (http/json-response* resp)
          count-query (http/add-headers {:count (jdbc/get-result-count count-query)}))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (http/error-response e))
    (catch IllegalArgumentException e
      (http/error-response e))
    (catch org.postgresql.util.PSQLException e
      (if (= (.getSQLState e) "2201B")
        (do (log/debug e "Caught PSQL processing exception")
            (http/error-response (.getMessage e)))
        (throw e)))))
