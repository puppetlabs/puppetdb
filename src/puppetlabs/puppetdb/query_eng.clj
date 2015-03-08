(ns puppetlabs.puppetdb.query-eng
  (:require [puppetlabs.puppetdb.http :as pl-http]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.nodes :as nodes]
            [puppetlabs.puppetdb.query.environments :as environments]
            [puppetlabs.puppetdb.query.reports :as reports]
            [puppetlabs.puppetdb.query.catalogs :as catalogs]
            [puppetlabs.puppetdb.query.factsets :as factsets]
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as scf-utils]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]
            [clojure.tools.logging :as log]))

(defn ignore-engine-params
  "Query engine munge functions should take two arguments, a version
   and a list of columns to project. Some of the munge funcitons don't
   currently adhere to that contract, so this function will wrap the
   given function `f` and ignore those arguments"
  [f]
  (fn [_ _ _]
    f))

;; TODO this is bad workaround because testing on this branch is currently
;; broken. It should be fixed before it gets merged.
;; The issue is that the events endpoint takes both query-options and
;; paging-options, and we're currently handling that case by passing it along
;; as a vector. We should instead do something like passing an 'options' map
;; {:paging-options :query-options}, or possibly just generalize it to options
;; and combine the maps in the events case.

(defn expand-paging-options
  [options entity]
  (let [pg? (scf-utils/postgres?)]
    (if (= :events entity)
      (list (first options) (assoc (second options) :expand? pg?))
      (assoc options :expand? pg?))))

(defn stream-query-result
  "Given a query, and database connection, return a Ring response with the query
  results.
  If the query can't be parsed, a 400 is returned."
  [entity version query paging-options db output-fn]
  (let [[query->sql munge-fn]
        (case entity
          :facts [facts/query->sql facts/munge-result-rows]
          :event-counts [event-counts/query->sql event-counts/munge-result-rows]
          :aggregate-event-counts [aggregate-event-counts/query->sql (ignore-engine-params (comp (partial kitchensink/mapvals #(if (nil? %) 0 %)) first))]
          :fact-contents [fact-contents/query->sql fact-contents/munge-result-rows]
          :fact-paths [facts/fact-paths-query->sql facts/munge-path-result-rows]
          :events [events/query->sql events/munge-result-rows]
          :nodes [nodes/query->sql nodes/munge-result-rows]
          :environments [environments/query->sql (fn [_ _ _] identity)]
          :reports [reports/query->sql reports/munge-result-rows]
          :factsets [factsets/query->sql factsets/munge-result-rows]
          :resources [resources/query->sql resources/munge-result-rows]
          :edges [edges/query->sql edges/munge-result-rows]
          :catalogs [catalogs/query->sql catalogs/munge-result-rows])]
    (jdbc/with-transacted-connection db
      (let [paging-options-with-expand (expand-paging-options paging-options entity)
            {[sql & params] :results-query
             count-query :count-query
             projected-fields :projected-fields} (query->sql version query
                                                             paging-options-with-expand)
             query-error (promise)
             resp (output-fn
                   (fn [f]
                     (try
                       (jdbc/with-transacted-connection db
                         (query/streamed-query-result version sql params
                           (comp
                             f
                             #(do
                                (first %)
                                (deliver query-error nil)
                                %)
                             (munge-fn version projected-fields paging-options-with-expand))))
                       (catch java.sql.SQLException e
                         (deliver query-error e)
                         nil))))]
        (when @query-error
          (throw @query-error))
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
      (pl-http/error-response e))
    (catch org.postgresql.util.PSQLException e
      (if (= (.getSQLState e) "2201B")
        (do
          (log/debug e "Caught PSQL processing exception")
          (pl-http/error-response (.getMessage e)))
        (throw e)))))
