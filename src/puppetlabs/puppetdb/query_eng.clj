(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.core.match :as cm]
            [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.set :refer [rename-keys]]
            [murphy :refer [try! with-open!]]
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
            [puppetlabs.puppetdb.query.catalog-inputs :as inputs]
            [puppetlabs.puppetdb.query-eng.default-reports :as dr]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.utils.string-formatter :as formatter]
            [puppetlabs.puppetdb.query-eng.engine :as eng]
            [ring.util.io :as rio]
            [schema.core :as s])
  (:import
   (clojure.lang ExceptionInfo)
   (com.fasterxml.jackson.core JsonParseException)
   (java.io IOException InputStream)
   (org.postgresql.util PGobject PSQLException)
   (org.joda.time Period)))

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
    ;; Not a real entity, requested via query param
    :factsets-with-packages {:munge facts/munge-package-inventory
                             :rec eng/factsets-with-packages-query}
    :catalogs {:munge (constantly identity)
               :rec eng/catalog-query}
    :catalog-input-contents {:munge (constantly identity)
                             :rec eng/catalog-input-contents-query}
    :catalog-inputs {:munge inputs/munge-catalog-inputs
                             :rec eng/catalog-inputs-query}
    :nodes {:munge (constantly identity)
            :rec eng/nodes-query}
    ;; Not a real entity name of course -- requested by query parameter.
    :nodes-with-fact-expiration {:munge (constantly identity)
                                 :rec eng/nodes-query-with-fact-expiration}
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
                 (formatter/dashes->underscores (name entity)))))))

(defn orderable-columns
  [query-rec]
  (vec
   (for [[projection-key projection-value] (:projections query-rec)
         :when (:queryable? projection-value)]
     (keyword projection-key))))

(defn maybe-log-sql [logging-options gen-sql]
  (if-let [log-id (:log-id logging-options)]
    (let [sql-output (gen-sql)]
      (log/infof "%s:%s:%s" "PDBQuery" log-id (:results-query sql-output))
      sql-output)
    (gen-sql)))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
   return nodes matching the `query`."
  ([query entity version query-options]
   (query->sql query entity version query-options {}))
  ([query entity version query-options logging-options]
   {:pre  [((some-fn nil? sequential?) query)]
    :post [(map? %)
           (jdbc/valid-jdbc-query? (:results-query %))
           (or (not (:include_total query-options))
               (jdbc/valid-jdbc-query? (:count-query %)))]}

   (maybe-log-sql
    logging-options
    (fn []
      (cond
        (= :aggregate-event-counts entity)
        (aggregate-event-counts/query->sql version query query-options)

        (= :event-counts entity)
        (event-counts/query->sql version query query-options)

        (and (= :events entity) (:distinct_resources query-options))
        (events/legacy-query->sql false version query query-options)

        :else
        (let [query-rec (cond
                          ;; Move this so that we always look for
                          ;; include_facts_expiration (PDB-4586).
                          (and (:include_facts_expiration query-options)
                               (= entity :nodes))
                          (get-in @entity-fn-idx [:nodes-with-fact-expiration :rec])

                          (and (:include_package_inventory query-options)
                               (= entity :factsets))
                          (get-in @entity-fn-idx [:factsets-with-packages :rec])

                          :else
                          (get-in @entity-fn-idx [entity :rec]))
              columns (orderable-columns query-rec)
              unknown-err-msg (trs "Unknown exception when processing ast to add report type filter(s).")]
          (paging/validate-order-by! columns query-options)
          (if (:add-agent-report-filter query-options)
            (let [ast (try
                        (dr/maybe-add-agent-report-filter-to-query query-rec query)
                        (catch ExceptionInfo e
                          (let [data (ex-data e)
                                msg (.getMessage e)]
                            (when (not= ::dr/unrecognized-ast-syntax (:kind data))
                              (log/error e unknown-err-msg)
                              (throw e))
                            (log/error e msg)
                            ::failed))
                        (catch Exception e
                          (log/error e unknown-err-msg)
                          (throw e)))]
              (if (= ast ::failed)
                (throw (ex-info "AST validation failed, but was successfully converted to SQL. Please file a PuppetDB ticket at https://tickets.puppetlabs.com"
                                {:kind ::dr/unrecognized-ast-syntax
                                 :ast query
                                 :sql (eng/compile-user-query->sql query-rec query query-options)}))
                (eng/compile-user-query->sql query-rec ast query-options)))
            (eng/compile-user-query->sql query-rec query query-options))))))))

(defn get-munge-fn
  [entity version paging-options url-prefix]
  (let [munge-fn (get-munge entity)]
    (case entity
      :event-counts
      (munge-fn (:summarize_by paging-options) version url-prefix)

      (munge-fn version url-prefix))))

(def use-preferred-streaming-method?
  (->> (or (System/getenv "PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD") "yes")
       (re-matches #"yes|true|1") seq not))

(def query-options-schema
  {:scf-read-db s/Any
   :url-prefix String
   :node-purge-ttl Period
   :add-agent-report-filter Boolean
   (s/optional-key :warn-experimental) Boolean
   (s/optional-key :pretty-print) (s/maybe Boolean)
   (s/optional-key :log-queries) Boolean})

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
   (let [{:keys [scf-read-db url-prefix warn-experimental pretty-print log-queries]
          :or {warn-experimental true
               pretty-print false
               log-queries false}} options
         log-id (when log-queries (str (java.util.UUID/randomUUID)))
         {:keys [remaining-query entity]} (eng/parse-query-context version query warn-experimental)
         munge-fn (get-munge-fn entity version paging-options url-prefix)]

     (when log-queries
       ;; log the AST of the incoming query
       (log/infof "%s:%s:%s" "PDBQuery" log-id query))

     (let [f #(let [{:keys [results-query]}
                    (query->sql remaining-query entity version
                                paging-options {:log-id log-id})]
                (jdbc/call-with-array-converted-query-rows results-query
                                                           (comp row-fn munge-fn)))]
       (if use-preferred-streaming-method?
         (jdbc/with-db-connection scf-read-db (jdbc/with-db-transaction [] (f)))
         (jdbc/with-transacted-connection scf-read-db (f)))))))

;; Do we still need this, i.e. do we need the pass-through, and the
;; strict selectivity in the caller below?
(defn- coerce-from-json
  "Parses obj as JSON if it's a string/stream/reader, otherwise
  returns obj."
  [obj]
  (cond
    (string? obj) (json/parse-strict obj true)
    (instance? java.io.Reader obj) (json/parse obj true)
    (instance? java.io.InputStream obj) (json/parse obj true)
    :else obj))

(defn user-query->engine-query
  ([version query-map options] (user-query->engine-query version query-map options false))
  ([version query-map options warn-experimental]
   (let [query (:query query-map)
         {:keys [remaining-query entity paging-clauses]} (eng/parse-query-context
                                                          version query warn-experimental)
         paging-options (some-> paging-clauses
                                (rename-keys {:order-by :order_by})
                                (update :order_by paging/munge-query-ordering)
                                utils/strip-nil-values)
         query-options (->> (dissoc query-map :query)
                            utils/strip-nil-values
                            (merge {:limit nil :offset nil :order_by nil}
                                   options
                                   paging-options))
         entity (cond
                  (and (= entity :factsets) (:include_package_inventory query-options)) :factsets-with-packages
                  :else entity)]
     {:query query :remaining-query remaining-query :entity entity :query-options query-options})))

(defn- generated-stream
  "Creates an InputStream connected to an OutputStream.  Runs (f
  out-stream) in a future, and returns the input stream after
  arranging for any exceptions thrown by f to be rethrown from by
  either the InputStream read or close methods."
  [f]
  (let [err (atom nil)
        with-ex-forwarding (fn [action]
                             (when-let [ex @err] (throw ex))
                             (let [result (action)]
                               (when-let [ex @err] (throw ex))
                               result))
        stream (rio/piped-input-stream
                (fn [out]
                  (try
                    (f out)
                    (catch Throwable ex
                      (reset! err ex)))))]
    (proxy [InputStream] []
      (available [] (.available stream))
      (close [] (with-ex-forwarding #(.close stream)))
      (mark [readlimit] (.mark stream readlimit))
      (markSupported [] (.markSupported stream))
      (read
        ([] (with-ex-forwarding #(.read stream)))
        ([b] (with-ex-forwarding #(.read stream b)))
        ([b off len] (with-ex-forwarding #(.read stream b off len))))
      (reset [] (.reset stream))
      (skip [n] (.skip stream n)))))

(defn- body-stream
  "Returns a map whose :stream is an InputStream that produces (via a
  future) the results of the query as JSON, and whose :status is a
  promise to which nil or {:count n} will be delivered after the first
  row is retrieved, or to which {:error exception} will be delivered
  if an exception happens before then.  An exception thrown by the
  future after that point will produce an exception from the next call
  to the InputStream read or close methods."
  [db query entity version query-options log-id munge-fn pretty-print]
  ;; Client disconnects present as generic IOExceptions from the
  ;; output writer (via stream-json), and we just log them at debug
  ;; level.  For now, after the first row, there's nothing we can do
  ;; to signal an error to the client making the query, other than
  ;; halting transmission and closing the connection, which happens
  ;; when the InputStream we return throws from its read or close
  ;; methods.  To provide more meaningful errors to the client, we
  ;; could add a new streaming protocol, i.e. response elements could
  ;; be either an entity map or some status (timeout, error, ...)
  ;; indicator.  We could also consider chunked transfer encoding with
  ;; trailing headers, *if* clients support is good enough.
  ;;
  ;; produce-streaming-body blocks until status is delivered, so
  ;; ensure it always is.
  (let [status (promise)
        quiet-exit (Exception. "private singleton escape exception escaped")
        stream (generated-stream
                ;; Runs in a future
                (fn [out]
                  (with-open! [out (io/writer out :encoding "UTF-8")]
                    (try
                      (jdbc/with-db-connection db
                        (jdbc/with-db-transaction []
                          (let [{:keys [results-query count-query]}
                                (query->sql query entity version query-options
                                            {:log-id log-id})
                                st (when count-query
                                     {:count (jdbc/get-result-count count-query)})
                                stream-row (fn [row]
                                             (let [r (munge-fn row)]
                                               (when-not (instance? PGobject r)
                                                 (first r))
                                               (when-not (realized? status)
                                                 (deliver status st))
                                               (try
                                                 (http/stream-json r out pretty-print)
                                                (catch IOException ex
                                                   (log/debug ex (trs "Unable to stream response: {0}"
                                                                      (.getMessage ex)))
                                                   (throw quiet-exit)))))]
                            (jdbc/call-with-array-converted-query-rows results-query
                                                                       stream-row)
                            (when-not (realized? status)
                              (deliver status st)))))
                      (catch Exception ex
                        ;; If it's an exit, we've already handled it.
                        (when-not (identical? quiet-exit ex)
                          (if (realized? status)
                            (throw ex)
                            (deliver status {:error ex}))))
                      (catch Throwable ex
                        (if (realized? status)
                          (do
                            (log/error ex (trs "Query streaming failed: {0} {1}"
                                               query query-options))
                            (throw ex))
                          (deliver status {:error ex})))))))]
    {:status status
     :stream stream}))

(defn preferred-produce-streaming-body
  [version query-map options]
  (let [{:keys [scf-read-db url-prefix warn-experimental pretty-print log-queries]
         :or {warn-experimental true
              pretty-print false
              log-queries false}} options
        log-id (when log-queries (str (java.util.UUID/randomUUID)))
        query-config (select-keys options [:node-purge-ttl :add-agent-report-filter])
        {:keys [query remaining-query entity query-options]}
        (user-query->engine-query version query-map query-config warn-experimental)]

    (when log-queries
      ;; log the AST of the incoming query
      (log/infof "%s:%s:%s" "PDBQuery" log-id (:query query-map)))

    (try
      (let [munge-fn (get-munge-fn entity version query-options url-prefix)
            {:keys [status stream]} (body-stream scf-read-db
                                                 (coerce-from-json remaining-query)
                                                 entity version query-options
                                                 log-id munge-fn pretty-print)]
        (let [{:keys [count error]} @status]
          (when error
            (throw error))
          (cond-> (http/json-response* stream)
            count (http/add-headers {:count count}))))
      (catch JsonParseException ex
        (log/error ex (trs "Unparsable query: {0} {1} {2}" log-id query query-options))
        (http/error-response ex))
      (catch IllegalArgumentException ex ;; thrown by (at least) munge-fn
        (log/error ex (trs "Invalid query: {0} {1} {2}" log-id query query-options))
        (http/error-response ex))
      (catch PSQLException ex
        (when-not (= (.getSQLState ex) (jdbc/sql-state :invalid-regular-expression))
          (throw ex))
        (do
          (log/debug ex (trs "Invalid query regex: {0} {1} {2}" log-id query query-options))
          (http/error-response ex))))))

;; for testing via with-redefs
(def munge-fn-hook identity)

(defn- deprecated-produce-streaming-body
  [version query-map options]
  (let [{:keys [scf-read-db url-prefix warn-experimental pretty-print log-queries]
         :or {warn-experimental true
              pretty-print false
              log-queries false}} options
        log-id (when log-queries (str (java.util.UUID/randomUUID)))
        query-config (select-keys options [:node-purge-ttl :add-agent-report-filter])
        {:keys [query remaining-query entity query-options]}
        (user-query->engine-query version query-map query-config warn-experimental)]

    (when log-queries
      ;; log the AST of the incoming query
      (log/infof "%s:%s:%s" "PDBQuery" log-id (:query query-map)))

    (try
      (jdbc/with-transacted-connection scf-read-db
        (let [munge-fn (get-munge-fn entity version query-options url-prefix)
              {:keys [results-query count-query]} (-> remaining-query
                                                      coerce-from-json
                                                      (query->sql entity
                                                                  version
                                                                  query-options
                                                                  {:log-id log-id}))
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
                                  (munge-fn-hook munge-fn))))
                         ;; catch throwable to make sure any trouble is forwarded to the
                         ;; query-error promise below. If something throws and is not passed
                         ;; along the deref will block indefinitely.
                         (catch Throwable e
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
        (if (= (.getSQLState e) (jdbc/sql-state :invalid-regular-expression))
          (do (log/debug e (trs "Caught PSQL processing exception"))
              (http/error-response (.getMessage e)))
          (throw e))))))

(pls/defn-validated produce-streaming-body
  "Given a query, and database connection, return a Ring response with
   the query results. query-map is a clojure map of the form
   {:query ['=','certname','foo'] :order_by [{'field':'foo'}]...}
   If the query can't be parsed, a 400 is returned."
  [version :- s/Keyword
   query-map
   options :- query-options-schema]
  (if use-preferred-streaming-method?
    (preferred-produce-streaming-body version query-map options)
    (deprecated-produce-streaming-body version query-map options)))

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
