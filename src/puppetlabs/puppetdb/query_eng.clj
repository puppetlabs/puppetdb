(ns puppetlabs.puppetdb.query-eng
  (:require [puppetlabs.puppetdb.http :as pl-http]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.nodes :as nodes]
            [puppetlabs.puppetdb.query.environments :as environments]
            [puppetlabs.puppetdb.query.reports :as reports]
            [puppetlabs.puppetdb.query.factsets :as factsets]
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query :as query]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]))

(defn stream-query-result
  "Given a query, and database connection, return a Ring response with the query
  results.
  If the query can't be parsed, a 400 is returned."
  [entity version query paging-options db output-fn]
  (let [[query->sql munge-fn]
        (case entity
          :facts [facts/query->sql (facts/munge-result-rows version)]
          :event-counts [event-counts/query->sql (event-counts/munge-result-rows (first paging-options))]
          :aggregate-event-counts [aggregate-event-counts/query->sql
                                   (comp (partial kitchensink/mapvals #(if (nil? %) 0 %)) first)]
          :fact-contents [fact-contents/query->sql fact-contents/munge-result-rows]
          :fact-paths [facts/fact-paths-query->sql facts/munge-path-result-rows]
          :events [events/query->sql (events/munge-result-rows version)]
          :nodes [nodes/query->sql (nodes/munge-result-rows version)]
          :environments [environments/query->sql identity]
          :reports [reports/query->sql (reports/munge-result-rows version)]
          :factsets [factsets/query->sql (factsets/munge-result-rows version)]
          :resources [resources/query->sql (resources/munge-result-rows version)])]
    (jdbc/with-transacted-connection db
      (let [{[sql & params] :results-query
             count-query :count-query} (query->sql version query
                                                   paging-options)
             resp (output-fn
                   (fn [f]
                     (jdbc/with-transacted-connection db
                       (query/streamed-query-result version sql params
                                                    (comp f munge-fn)))))]
        (if count-query
          (http/add-headers resp {:count (jdbc/get-result-count count-query)})
          resp)))))

(defn produce-streaming-body
  "Given a query, and database connection, return a Ring response with the query
  results.
  If the query can't be parsed, a 400 is returned."
  [entity version query paging-options db]
  (try
    (let [parsed-query (json/parse-strict-string query true)]
      (stream-query-result entity version parsed-query paging-options db pl-http/stream-json-response))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))
