(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
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
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.puppetdb.scf.storage-utils :as su]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(defn entity->sql-fns
  [entity version paging-options url-prefix]
  (let [[query->sql munge-fn]
        (case entity
          :aggregate-event-counts [aggregate-event-counts/query->sql
                                   aggregate-event-counts/munge-result-rows]
          :event-counts [event-counts/query->sql
                         (event-counts/munge-result-rows (first paging-options))]
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
  ([entity version query paging-options db url-prefix]
   ;; We default to doall because tests need this for the most part
   (stream-query-result entity version query paging-options db url-prefix doall))
  ([entity version query paging-options db url-prefix row-fn]
   (let [[query->sql munge-fn] (entity->sql-fns entity version paging-options url-prefix)]
     (jdbc/with-transacted-connection db
       (let [{:keys [results-query]} (query->sql query)]
         (jdbc/with-query-results-cursor results-query (comp row-fn munge-fn)))))))

(defn produce-streaming-body
  "DEPRECATED - this function will be replaced by produce-streaming-body'
                which accepts a query map

  Given a query, and database connection, return a Ring response with
  the query results. `query` is either a string (if it's coming from a
  GET request) or an already parsed clojure data structure (if it's
  from a POST request).

  If the query can't be parsed, a 400 is returned."
  {:deprecated "3.0.0"}
  [entity version query paging-options db url-prefix]
  (try
    (jdbc/with-transacted-connection db
      (let [[query->sql munge-fn] (entity->sql-fns entity version paging-options url-prefix)
            {:keys [results-query count-query]} (-> query json/coerce-from-json query->sql)
            query-error (promise)
            resp (http/streamed-response
                  buffer
                  (try (jdbc/with-transacted-connection db
                         (jdbc/with-query-results-cursor
                          results-query (comp #(http/stream-json % buffer)
                                              #(do (first %) (deliver query-error nil) %)
                                              munge-fn)))
                       (catch java.sql.SQLException e
                         (deliver query-error e))))]
        (if @query-error
          (throw @query-error)
          (cond-> (http/json-response* resp)
            count-query (http/add-headers {:count (jdbc/get-result-count count-query)})))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (log/errorf e (str "Error executing query '%s' for entity '%s' "
                         "with paging-options '%s'. Returning a 400 error code.")
                  (name entity) query paging-options)
      (http/error-response e))
    (catch IllegalArgumentException e
      (log/errorf e (str "Error executing query '%s' for entity '%s' "
                         "with paging-options '%s'. Returning a 400 error code.")
                  (name entity) query paging-options)
      (http/error-response e))
    (catch org.postgresql.util.PSQLException e
      (if (= (.getSQLState e) "2201B")
        (do (log/debug e "Caught PSQL processing exception")
            (http/error-response (.getMessage e)))
        (throw e)))))

(defn produce-streaming-body'
  "Same as `produce-streaming-body` but accepts a query map instead. These two functions will eventually merge"
  [entity version query-map db url-prefix]
  (produce-streaming-body entity version (:query query-map) (dissoc query-map :query) db url-prefix))

(pls/defn-validated object-exists? :- s/Bool
  "Returns true if an object exists."
  [type :- s/Keyword
   id :- s/Str]
  (let [check-sql (case type
                    :catalog "SELECT 1
                              FROM certnames
                              INNER JOIN catalogs
                                ON catalogs.certname = certnames.certname
                              WHERE certnames.certname=?"
                    :node "SELECT 1
                           FROM certnames
                           WHERE certname=? "
                    :report (str "SELECT 1
                                  FROM reports
                                  WHERE " (su/sql-hash-as-str "hash") "=?
                                  LIMIT 1")
                    :environment "SELECT 1
                                  FROM environments
                                  WHERE name=?"
                    :factset "SELECT 1
                              FROM certnames
                              INNER JOIN factsets
                              ON factsets.certname = certnames.certname
                              WHERE certnames.certname=?")]
    (sql/with-query-results result-set
      [check-sql id]
      (pos? (count result-set)))))
