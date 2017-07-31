(ns puppetlabs.puppetdb.query-eng
  (:import [org.postgresql.util PGobject])
  (:require [clojure.core.match :as cm]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.set :refer [rename-keys]]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query-eng.engine :as eng]
            [schema.core :as s]))

(def entity-fn-idx
  (atom
   {:aggregate-event-counts {:munge aggregate-event-counts/munge-result-rows
                             :rec nil}
    :event-counts {:munge event-counts/munge-result-rows
                   :rec nil}
    :inventory {:munge (constantly identity)
                :rec eng/inventory-query}
    :facts {:munge facts/munge-result-rows
            :rec eng/facts-query}
    :fact-contents {:munge fact-contents/munge-result-rows
                    :rec eng/fact-contents-query}
    :fact-paths {:munge facts/munge-path-result-rows
                 :rec eng/fact-paths-query}
    :fact-names {:munge facts/munge-name-result-rows
                 :rec eng/fact-names-query}
    :factsets {:munge (constantly identity)
               :rec eng/factsets-query}
    :catalogs {:munge (constantly identity)
               :rec eng/catalog-query}
    :nodes {:munge (constantly identity)
            :rec eng/nodes-query}
    :environments {:munge (constantly identity)
                   :rec eng/environments-query}
    :producers {:munge (constantly identity)
                :rec eng/producers-query}
    :packages {:munge (constantly identity)
               :rec eng/packages-query}
    :package-inventory {:munge (constantly identity)
                        :rec eng/package-inventory-query}
    :events {:munge events/munge-result-rows
             :rec eng/report-events-query}
    :edges {:munge edges/munge-result-rows
            :rec eng/edges-query}
    :reports {:munge (constantly identity)
              :rec eng/reports-query}
    :report-metrics {:munge (constantly (comp :metrics first))
                     :rec eng/report-metrics-query}
    :report-logs {:munge (constantly (comp :logs first))
                  :rec eng/report-logs-query}
    :resources {:munge resources/munge-result-rows
                :rec eng/resources-query}}))

(defn get-munge
  [entity]
  (if-let [munge-result (get-in @entity-fn-idx [entity :munge])]
    munge-result
    (throw (IllegalArgumentException.
            (tru "Invalid entity ''{0}'' in query"
                 (utils/dashes->underscores (name entity)))))))

(defn orderable-columns
  [query-rec]
  (vec
   (for [[projection-key projection-value] (:projections query-rec)
         :when (and (not= projection-key "value")
                    (:queryable? projection-value))]
     (keyword projection-key))))

(defn legacy-engine-query-context
  "Parse a query and possibly some paging clauses from a query to the legacy
   engine. Required because top-level paging options supplied in the query are
   not otherwise handled by the engine, so they must be merged with
   param-supplied paging options and dealt with at the top level."
  [version query warn]
  (cm/match
    query
    ["from" (entity-str :guard #(string? %)) & remaining-query]
    (let [remaining-query (cm/match
                            remaining-query

                            [(c :guard eng/paging-clause?) & clauses]
                            (let [paging-clauses (eng/create-paging-map (cons c clauses))]
                              {:paging-clauses paging-clauses :query []})

                            [(q :guard vector?)]
                            {:query q}

                            [(q :guard vector?) & clauses]
                            (let [paging-clauses (eng/create-paging-map clauses)]
                              {:query q :paging-clauses paging-clauses})

                            []
                            {:query []}
                            :else (throw
                                    (IllegalArgumentException.
                                      (str
                                        (tru "Your `from` query accepts an optional query only as a second argument.")
                                        " "
                                        (tru "Check your query and try again.")))))
          entity (keyword (utils/underscores->dashes entity-str))]
      (when warn
        (eng/warn-experimental entity))
      {:remaining-query (:query remaining-query)
       :paging-clauses (:paging-clauses remaining-query)})

    :else (throw (IllegalArgumentException.
                   (str
                     (trs "Your initial query must be of the form: [\"from\",<entity>,(<optional-query>)].")
                     " "
                     (trs "Check your query and try again."))))))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
   return nodes matching the `query`."
  [query entity version paging-options]
  {:pre  [((some-fn nil? sequential?) query)]
   :post [(map? %)
          (jdbc/valid-jdbc-query? (:results-query %))
          (or (not (:include_total paging-options))
              (jdbc/valid-jdbc-query? (:count-query %)))]}


  (if (or (= :aggregate-event-counts entity)
          (= :event-counts entity)
          (and (= :events entity) (:distinct_resources paging-options)))

    (let [{:keys [remaining-query paging-clauses]} (legacy-engine-query-context version query true)
          paging-clauses (some-> paging-clauses
                                 (rename-keys {:order-by :order_by})
                                 (update :order_by paging/munge-query-ordering)
                                 utils/strip-nil-values)
          paging-options (merge paging-options (utils/strip-nil-values paging-clauses))]
      (cond
        (= :aggregate-event-counts entity)
        (aggregate-event-counts/query->sql version remaining-query paging-options)

        (= :event-counts entity)
        (event-counts/query->sql version remaining-query paging-options)

        (and (= :events entity) (:distinct_resources paging-options))
        (events/legacy-query->sql false version remaining-query paging-options)))

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

(def query-options-schema
  {:scf-read-db s/Any
   :url-prefix String
   (s/optional-key :warn-experimental) Boolean
   (s/optional-key :pretty-print) (s/maybe Boolean)})

(pls/defn-validated stream-query-result
  "Given a query, and database connection, return a Ring response with the query
   results."
  ([version query paging-options options]
   ;; We default to doall because tests need this for the most part
   (stream-query-result version query paging-options options doall))
  ([version :- s/Keyword
    query
    paging-options
    options :- query-options-schema
    row-fn]
   (let [{:keys [scf-read-db url-prefix warn-experimental pretty-print]
          :or {warn-experimental true
               pretty-print false}} options
         {:keys [query entity]} (eng/parse-query-context version query warn-experimental)
         munge-fn (get-munge-fn entity version paging-options url-prefix)]
     (jdbc/with-transacted-connection scf-read-db
       (let [{:keys [results-query]}
             (query->sql query entity version paging-options)]
         (jdbc/call-with-array-converted-query-rows results-query
                                                    (comp row-fn munge-fn)))))))

;; Do we still need this, i.e. do we need the pass-through, and the
;; strict selectivity in the caller below?
(defn- coerce-from-json [obj]
  "Parses obj as JSON if it's a string/stream/reader, otherwise
  returns obj."
  (cond
    (string? obj) (json/parse-strict obj true)
    (instance? java.io.Reader obj) (json/parse obj true)
    (instance? java.io.InputStream obj) (json/parse obj true)
    :else obj))

(defn user-query->engine-query
  ([version query-map] (user-query->engine-query version query-map false))
  ([version query-map warn-experimental]
   (let [query (:query query-map)
         {:keys [query entity]} (eng/parse-query-context
                                  version query warn-experimental)
         query-options (->> (dissoc query-map :query)
                            utils/strip-nil-values)]
     {:query query :entity entity :query-options query-options})))

(pls/defn-validated produce-streaming-body
  "Given a query, and database connection, return a Ring response with
   the query results. query-map is a clojure map of the form
   {:query ['=','certname','foo'] :order_by [{'field':'foo'}]...}
   If the query can't be parsed, a 400 is returned."
  [version :- s/Keyword
   query-map
   options :- query-options-schema]
  (let [{:keys [scf-read-db url-prefix warn-experimental pretty-print]
         :or {warn-experimental true
              pretty-print false}} options
        {:keys [query entity query-options]} (user-query->engine-query version query-map warn-experimental)]

    (try
      (jdbc/with-transacted-connection scf-read-db
        (let [munge-fn (get-munge-fn entity version query-options url-prefix)
              {:keys [results-query count-query]} (-> query
                                                      coerce-from-json
                                                      (query->sql entity version query-options))
              query-error (promise)
              resp (http/streamed-response
                    buffer
                    (try (jdbc/with-transacted-connection scf-read-db
                           (jdbc/call-with-array-converted-query-rows
                            results-query
                            (comp #(http/stream-json % buffer pretty-print)
                                  #(do (when-not (instance? PGobject %)
                                         (first %))
                                       (deliver query-error nil) %)
                                  munge-fn)))
                         (catch java.sql.SQLException e
                           (deliver query-error e))))]
          (if @query-error
            (throw @query-error)
            (cond-> (http/json-response* resp)
                    count-query (http/add-headers {:count (jdbc/get-result-count count-query)})))))
      (catch com.fasterxml.jackson.core.JsonParseException e
        (log/error
         e
         (trs "Error executing query ''{0}'' with query options ''{1}''. Returning a 400 error code."
              query query-options))
        (http/error-response e))
      (catch IllegalArgumentException e
        (log/error
         e
         (trs
          "Error executing query ''{0}'' with query options ''{1}''. Returning a 400 error code."
          query query-options))
        (http/error-response e))
      (catch org.postgresql.util.PSQLException e
        (if (= (.getSQLState e) "2201B")
          (do (log/debug e (trs "Caught PSQL processing exception"))
              (http/error-response (.getMessage e)))
          (throw e))))))

(pls/defn-validated object-exists? :- s/Bool
  "Returns true if an object exists."
  [type :- s/Keyword
   id :- s/Str]
  (let [check-sql (case type
                    :catalog "SELECT 1
                              FROM CATALOGS
                              where catalogs.certname=?"
                    :node "SELECT 1
                           FROM certnames
                           WHERE certname=? "
                    :report (str "SELECT 1
                                  FROM reports
                                  WHERE " (sutils/sql-hash-as-str "hash") "=?
                                  LIMIT 1")
                    :environment "SELECT 1
                                  FROM environments
                                  WHERE environment=?"
                    :producer "SELECT 1
                                  FROM producers
                                  WHERE name=?"
                    :factset "SELECT 1
                              FROM certnames
                              INNER JOIN factsets
                              ON factsets.certname = certnames.certname
                              WHERE certnames.certname=?")]
    (jdbc/query-with-resultset [check-sql id]
                               (comp boolean seq sql/result-set-seq))))
