(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.java.jdbc.deprecated :as sql]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.catalogs :as catalogs]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.factsets :as factsets]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.reports :as reports]
            [puppetlabs.puppetdb.query.report-data :as report-data]
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.puppetdb.scf.storage-utils :as su]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query-eng.engine :as eng]
            [schema.core :as s]))

(def entity-fn-idx
  (atom
    {:aggregate-event-counts {:munge aggregate-event-counts/munge-result-rows
                              :rec nil}
     :event-counts {:munge event-counts/munge-result-rows
                    :rec nil}
     :facts {:munge facts/munge-result-rows
             :rec eng/facts-query}
     :fact-contents {:munge fact-contents/munge-result-rows
                     :rec eng/fact-contents-query}
     :fact-paths {:munge facts/munge-path-result-rows
                  :rec eng/fact-paths-query}
     :fact-names {:munge facts/munge-name-result-rows
                  :rec eng/fact-names-query}
     :factsets {:munge factsets/munge-result-rows
                :rec eng/factsets-query}
     :catalogs {:munge catalogs/munge-result-rows
                :rec eng/catalog-query}
     :nodes {:munge (constantly identity)
             :rec eng/nodes-query}
     :environments {:munge (constantly identity)
                    :rec eng/environments-query}
     :events {:munge events/munge-result-rows
              :rec eng/report-events-query}
     :edges {:munge edges/munge-result-rows
             :rec eng/edges-query}
     :reports {:munge reports/munge-result-rows
               :rec eng/reports-query}
     :report-metrics {:munge (report-data/munge-result-rows :metrics)
                      :rec eng/report-metrics-query}
     :report-logs {:munge (report-data/munge-result-rows :logs)
                   :rec eng/report-logs-query}
     :resources {:munge resources/munge-result-rows
                 :rec eng/resources-query}}))

(defn assoc-in-idx!
  "assoc into the entity index as if query recs were maps"
  [[entity & ks] v]
  (if (and (= :rec (first ks)) (next ks))
    ;; if it's a query rec and we're updating a nested component, call the
    ;; existing rec and wrap val in a function
    (let [rec' (-> ((get-in @entity-fn-idx [entity :rec]))
                   (assoc-in (next ks) v))]
      (swap! entity-fn-idx assoc-in [entity :rec] (fn [] rec')))
    (swap! entity-fn-idx assoc-in (cons entity ks) v)))

(defn get-munge
  [entity]
  (get-in @entity-fn-idx [entity :munge]))

(defn orderable-columns
  [query-rec]
  (if-not query-rec
    []
    (for [[projection-key projection-value] (:projections (query-rec))
          :when (and (not= projection-key "value") (:queryable? projection-value))]
      (keyword projection-key))))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
   return nodes matching the `query`."
  [query entity version paging-options]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(map? %)
          (jdbc/valid-jdbc-query? (:results-query %))
          (or (not (:include_total paging-options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}

  (cond
    (= :aggregate-event-counts entity)
    (aggregate-event-counts/query->sql version query paging-options)

    (= :event-counts entity)
    (event-counts/query->sql version query paging-options)

    (and (= :events entity) (:distinct_resources paging-options))
    (events/legacy-query->sql false version query paging-options)

    :else
    (let [query-rec (get-in @entity-fn-idx [entity :rec])
          columns (orderable-columns query-rec)]
      (paging/validate-order-by! columns paging-options)
      (eng/compile-user-query->sql query-rec query paging-options))))

(defn get-munge-fn
  [entity version paging-options url-prefix]
  (let [munge-fn (get-munge entity)]
    (case entity
      :event-counts
      (munge-fn (:summarize_by paging-options) version url-prefix)

      (munge-fn version url-prefix))))

(defn stream-query-result
  "Given a query, and database connection, return a Ring response with the query
   results."
  ([entity version query paging-options db url-prefix]
   ;; We default to doall because tests need this for the most part
   (stream-query-result entity version query paging-options db url-prefix doall))
  ([entity version query paging-options db url-prefix row-fn]
   (let [munge-fn (get-munge-fn entity version paging-options url-prefix)]
     (jdbc/with-transacted-connection db
       (let [{:keys [results-query]}
             (query->sql query entity version paging-options)]
         (jdbc/with-query-results-cursor results-query (comp row-fn munge-fn)))))))

(defn produce-streaming-body
  "Given a query, and database connection, return a Ring response with
   the query results. `query` is either a string (if it's coming from a
   GET request) or an already parsed clojure data structure (if it's
   from a POST request).

   If the query can't be parsed, a 400 is returned."
  [entity version query-map db url-prefix]
  (let [query (:query query-map)
        query-options (dissoc query-map :query)]
    (try
      (jdbc/with-transacted-connection db
        (let [munge-fn (get-munge-fn entity version query-options url-prefix)
              {:keys [results-query count-query]} (-> query
                                                      json/coerce-from-json
                                                      (query->sql entity version query-options))
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
                           "with query-options '%s'. Returning a 400 error code.")
                    (name entity) query query-options)
        (http/error-response e))
      (catch IllegalArgumentException e
        (log/errorf e (str "Error executing query '%s' for entity '%s' "
                           "with query-options '%s'. Returning a 400 error code.")
                    (name entity) query query-options)
        (http/error-response e))
      (catch org.postgresql.util.PSQLException e
        (if (= (.getSQLState e) "2201B")
          (do (log/debug e "Caught PSQL processing exception")
              (http/error-response (.getMessage e)))
          (throw e))))))

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
                                  WHERE environment=?"
                    :factset "SELECT 1
                              FROM certnames
                              INNER JOIN factsets
                              ON factsets.certname = certnames.certname
                              WHERE certnames.certname=?")]
    (sql/with-query-results result-set
      [check-sql id]
      (pos? (count result-set)))))
