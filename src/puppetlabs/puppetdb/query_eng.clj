(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.set :refer [rename-keys]]
            [murphy :refer [with-open! try!]]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng.default-reports :as dr]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.puppetdb.query.catalog-inputs :as inputs]
            [puppetlabs.puppetdb.query.monitor :as qmon]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils
             :refer [with-log-mdc time-limited-seq]]
            [puppetlabs.puppetdb.utils.string-formatter :as formatter]
            [puppetlabs.puppetdb.query-eng.engine :as eng]
            [ring.util.io :as rio]
            [schema.core :as s])
  (:import
   (clojure.lang ExceptionInfo)
   (com.fasterxml.jackson.core JsonParseException)
   (java.io IOException InputStream)
   (java.nio.channels SelectionKey)
   (java.sql SQLException)
   (org.postgresql.util PGobject PSQLException)
   (org.joda.time Period)))

(defn query-terminated-msg [id origin]
  (if origin
    (trs "PDBQuery:{0}: from {1} was terminated in Postgres" id (pr-str origin))
    (trs "PDBQuery:{0}: was terminated in Postgres" id)))

(defn query-terminated [id origin]
  ;; Note: the exception message is currently logged directly in some cases.
  (ex-info (query-terminated-msg id origin)
           {:kind :puppetlabs.puppetdb.query/terminated :id id :origin origin}))

(defn query-timeout [id origin]
  ;; Note: the exception message is currently logged directly in some cases.
  (ex-info (if origin
             (trs "PDBQuery:{0}: from {1} exceeded timeout" id (pr-str origin))
             (trs "PDBQuery:{0}: exceeded timeout" id))
           {:kind :puppetlabs.puppetdb.query/timeout :id id :origin origin}))

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

(defn- regular-query->sql
  [query entity options]
  (let [query-rec (cond
                    ;; Move this so that we always look for
                    ;; include_facts_expiration (PDB-4586).
                    (and (:include_facts_expiration options)
                         (= entity :nodes))
                    (get-in @entity-fn-idx [:nodes-with-fact-expiration :rec])

                    (and (:include_package_inventory options)
                         (= entity :factsets))
                    (get-in @entity-fn-idx [:factsets-with-packages :rec])

                    :else
                    (get-in @entity-fn-idx [entity :rec]))
        columns (orderable-columns query-rec)]
    (paging/validate-order-by! columns options)
    (if (:add-agent-report-filter options)
      (let [ast (try
                  (dr/maybe-add-agent-report-filter-to-query query-rec query)
                  (catch ExceptionInfo e
                    (let [data (ex-data e)
                          msg (.getMessage e)]
                      (when (not= ::dr/unrecognized-ast-syntax (:kind data))
                        (log/error e (trs "Unknown exception when processing ast to add report type filter(s)."))
                        (throw e))
                      (log/error e msg)
                      {:type ::failed, :message msg}))
                  (catch Exception e
                    (log/error e (trs "Unknown exception when processing ast to add report type filter(s)."))
                    (throw e)))]
        (if (and (map? ast) (= (:type ast) ::failed))
          (throw (ex-info (trs "AST validation failed, but was successfully converted to SQL. Please file a PuppetDB ticket at https://tickets.puppetlabs.com \n{0}"
                               (:message ast))
                          {:kind ::dr/unrecognized-ast-syntax
                           :ast query
                           :sql (eng/compile-user-query->sql query-rec query options)}))
          (eng/compile-user-query->sql query-rec ast options)))
      (eng/compile-user-query->sql query-rec query options))))

(defn query->sql
  "Converts a vector-structured `query` to a corresponding SQL query which will
   return nodes matching the `query`."
  ([query entity version options]
   (query->sql query entity version options nil))
  ([query entity version options context]
   {:pre  [((some-fn nil? sequential?) query)]
    :post [(map? %)
           (jdbc/valid-jdbc-query? (:results-query %))
           (or (not (:include_total options))
               (jdbc/valid-jdbc-query? (:count-query %)))]}
   (let [result (cond
                  (= :aggregate-event-counts entity)
                  (aggregate-event-counts/query->sql version query options)

                  (= :event-counts entity)
                  (event-counts/query->sql version query options)

                  (and (= :events entity) (:distinct_resources options))
                  (events/legacy-query->sql false version query options)

                  :else (regular-query->sql query entity options))]
     (when (:log-queries context)
       (let [{[sql & params] :results-query} result]
         (log/infof "PDBQuery:%s:%s"
                    (:query-id context)
                    (-> (sorted-map :origin (:origin options)
                                    :sql sql
                                    :params (vec params))
                        json/generate-string))))
     result)))

(defn get-munge-fn
  [entity version paging-options url-prefix]
  (let [munge-fn (get-munge entity)]
    (if (= (:explain paging-options) :analyze)
      identity
      (case entity
        :event-counts
        (munge-fn (:summarize_by paging-options) version url-prefix)

        (munge-fn version url-prefix)))))

(def query-context-schema
  {:scf-read-db s/Any
   :url-prefix String
   :node-purge-ttl Period
   :add-agent-report-filter Boolean
   (s/optional-key :warn-experimental) Boolean
   (s/optional-key :pretty-print) (s/maybe Boolean)
   (s/optional-key :log-queries) Boolean
   (s/optional-key :query-id) s/Str
   (s/optional-key :query-deadline-ns) s/Num
   (s/optional-key :query-monitor) s/Any
   (s/optional-key :query-monitor-id) SelectionKey
   (s/optional-key :puppetlabs.puppetdb.config/test) {s/Any s/Any}})

(pls/defn-validated stream-query-result
  "Given a query, and database connection, call row-fn on the
  resulting (munged) row sequence."
  ([version query options context]
   ;; We default to doall because tests need this for the most part
   (stream-query-result version query options context doall))
  ([version :- s/Keyword
    query options
    context :- query-context-schema
    row-fn]
   ;; For now, generate the ids here; perhaps later, higher up
   (assert (not (:query-id context)))
   (let [query-id (str (java.util.UUID/randomUUID))
         context (assoc context :query-id query-id)
         origin (:origin options)]
     (with-log-mdc ["pdb-query-id" query-id
                    "pdb-query-origin" origin]
       (let [{:keys [scf-read-db url-prefix warn-experimental log-queries]
              :or {warn-experimental true}} context
             {:keys [remaining-query entity]} (eng/parse-query-context query warn-experimental)
             munge-fn (get-munge-fn entity version options url-prefix)
             throw-timeout #(throw (query-timeout query-id origin))
             {:keys [query-deadline-ns query-monitor query-monitor-id]} context]
         (when log-queries
           ;; Log origin and AST of incoming query
           (log/infof "PDBQuery:%s:%s"
                      query-id (-> (sorted-map :origin origin :ast query)
                                   json/generate-string)))
         (jdbc/with-db-connection scf-read-db
           (when query-monitor-id
             (qmon/register-pg-pid query-monitor query-monitor-id
                                   (jdbc/current-pid)))
           (try!
             (jdbc/with-db-transaction []
               (try
                 (jdbc/update-local-timeouts query-deadline-ns 1)
                 (let [{:keys [results-query]}
                       (query->sql remaining-query entity version
                                   (merge options
                                          (select-keys context [:node-purge-ttl :add-agent-report-filter]))
                                   (select-keys context [:log-queries :query-id]))]
                   (jdbc/call-with-array-converted-query-rows
                    results-query
                    (cond-> (comp row-fn munge-fn)
                      query-deadline-ns
                      (comp #(time-limited-seq % query-deadline-ns throw-timeout)))))
                 (catch ExceptionInfo ex
                   ;; Recast pg_terminate_backend errors since most(?)
                   ;; will be intentional via the query monitor, and
                   ;; we can't reliably distinguish others.
                   ;; cf. wrap-with-exception-handling.
                   (if (= (jdbc/sql-state :admin-shutdown)
                          (some-> (ex-data ex) :handling .getSQLState))
                     (throw (query-terminated query-id origin))
                     (throw ex)))
                 (catch SQLException ex
                   (if (jdbc/local-timeout-ex? ex)
                     (throw-timeout)
                     (throw ex)))))
             (finally
               (when query-monitor-id
                 (qmon/forget query-monitor query-monitor-id))))))))))

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
  ([version query-map options]
   (user-query->engine-query version query-map options false))
  ([_version query-map options warn-experimental]
   (let [query (:query query-map)

         {:keys [remaining-query entity paging-clauses]}
         (eng/parse-query-context query warn-experimental)

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

;; Could this just be a key in a query/context specific map?

;; Strictly for testing (seconds) - only works if all the threads
;; involved are related via binding conveyance.  Otherwise, we'll need
;; a redef.
(def ^:dynamic diagnostic-inter-row-sleep nil)

(defn- body-stream
  "Returns a map whose :stream is an InputStream that produces (via a
  future) the results of the query as JSON, and whose :status is a
  promise to which nil or {:count n} will be delivered after the first
  row is retrieved (if any), or to which {:error exception} will be
  delivered if an exception happens before then.  An exception thrown
  by the future after that point will produce an exception from the
  next call to the InputStream read or close methods."
  [db query entity version query-options munge-fn
   {:keys [pretty-print query-id query-deadline-ns query-monitor query-monitor-id]
    :as context}]
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

  (let [origin (:origin query-options)
        ;; This is used for cases where the consumer (jetty thread) is
        ;; presumed "gone", e.g. if the attempt to write json to it
        ;; throws an IO exception.  In that case, we don't want to
        ;; hand it an exception to log (presumably it already did
        ;; something), we just want to handle it ourselves and quit.
        ;; This must only be used after we've delivered the status,
        ;; since it (intentionally) doesn't deliver.
        quiet-exit (Exception. "private singleton escape exception escaped")
        throw-timeout #(throw (query-timeout query-id origin))
        status (promise)
        ;; The time-limited-seq should check for timeout before and
        ;; after realizing each row, which should allow us to time out
        ;; between the db pull and network push.  If we ever want to
        ;; handle db-side errors independently, we'll need to add a
        ;; pull-side try/catch (e.g., I think, the (seq s) in
        ;; time-delmited-seq) or to rewrite this as an explicit loop,
        ;; or...
        stream-rows (fn stream-rows [rows out status-after-query-start]
                      (let [timeout-seq #(time-limited-seq % query-deadline-ns throw-timeout)
                            rows (cond->> rows
                                   diagnostic-inter-row-sleep (map #(dissoc % :pg_sleep))
                                   true munge-fn
                                   query-deadline-ns timeout-seq)]
                        (when-not (instance? PGobject rows)
                          (first rows))
                        (deliver status status-after-query-start)
                        (try
                          (http/stream-json rows out pretty-print)
                          (catch IOException ex
                            (log/debug ex (trs "Unable to stream response: {0}"
                                               (.getMessage ex)))
                            (throw quiet-exit)))))]

    ;; Summarize all pg_terminate_backend errors in here since most(?)
    ;; will be produced by the query monitor, and we can't reliably
    ;; distinguish others.  cf. wrap-with-exception-handling which
    ;; handles the throws.
    {:status status
     :stream (generated-stream
              ;; Runs in a future
              (fn serialize-query-response [out]
                (try
                  (with-log-mdc ["pdb-query-id" query-id
                                 "pdb-query-origin" origin]
                    (with-open! [out (io/writer out :encoding "UTF-8")]
                      (try
                        (jdbc/with-db-connection db
                          (when query-monitor-id
                            (qmon/register-pg-pid query-monitor query-monitor-id
                                                  (jdbc/current-pid)))
                          (try!
                            (jdbc/with-db-transaction []
                              (jdbc/update-local-timeouts query-deadline-ns 1)
                              (let [{:keys [results-query count-query]}
                                    (query->sql query entity version query-options context)
                                    st (when count-query
                                         {:count (jdbc/get-result-count count-query)})
                                    add-sleep #(format "select pg_sleep(%f), * from (%s) as placeholder_name"
                                                       (float diagnostic-inter-row-sleep) %)
                                    results-query (cond-> results-query
                                                    diagnostic-inter-row-sleep (update 0 add-sleep))]
                                (jdbc/update-local-timeouts query-deadline-ns 1)
                                (jdbc/call-with-array-converted-query-rows
                                 results-query
                                 (when diagnostic-inter-row-sleep {:fetch-size 1})
                                 #(stream-rows % out st))))
                            (finally
                              (when query-monitor-id
                                (qmon/forget query-monitor query-monitor-id)))))
                        (catch SQLException ex
                          (if (jdbc/local-timeout-ex? ex)
                            (throw-timeout)
                            (throw ex)))
                        (catch ExceptionInfo ex
                          (cond
                            ;; If we've already sent the response to
                            ;; the client (i.e. status has been
                            ;; delivered) then send something "useful"
                            ;; to the client (at the end of the
                            ;; truncated JSON) before we close the
                            ;; connection.

                            (and (realized? status)
                                 (= :puppetlabs.puppetdb.query/timeout (:kind (ex-data ex))))
                            (let [msg (.getMessage ex)]
                              (log/warn msg)
                              (.write out msg) (.flush out))

                            (and (realized? status) (jdbc/clj-jdbc-termination-ex? ex))
                            (let [msg (query-terminated-msg query-id origin)]
                              (log/warn msg)
                              (.write out msg) (.flush out))

                            :else (throw ex))))))

                  ;; These delivers may not do anything, i.e. if the
                  ;; query has already started.
                  (catch Throwable ex
                    (cond
                      (identical? quiet-exit ex)
                      (when-not (realized? status)
                        (log/error ex (trs "Impossible situation: query streamer exiting without delivery")))

                      ;; connection to client has been closed at this point
                      (realized? status)
                      (cond
                        (and (instance? ExceptionInfo ex)
                             (= :puppetlabs.puppetdb.query/timeout (:kind (ex-data ex))))
                        (log/warn (.getMessage ex))

                        (and (instance? ExceptionInfo ex)
                             (jdbc/clj-jdbc-termination-ex? ex))
                        (log/warn (query-terminated-msg query-id origin))

                        :else
                        (let [msg (trs "Query streaming failed: {0} {1}" query query-options)]
                          (log/error ex msg)
                          (throw ex)))

                      :else (deliver status {:error ex}))))))}))

;; for testing via with-redefs
(def munge-fn-hook identity)

(pls/defn-validated produce-streaming-body
  "Given a query, and database connection, return a Ring response with
   the query results. query-map is a clojure map of the form
   {:query ['=','certname','foo'] :order_by [{'field':'foo'}]...}
   If the query can't be parsed, a 400 is returned."
  [version :- s/Keyword
   query-map
   query-uuid :- s/Str
   context :- query-context-schema]
  (let [context (assoc context :query-id query-uuid)]
    (with-log-mdc ["pdb-query-id" query-uuid
                   "pdb-query-origin" (:origin query-map)]
      (let [{:keys [scf-read-db url-prefix warn-experimental log-queries query-id]
             :or {warn-experimental true}} context
            query-config (select-keys context [:node-purge-ttl :add-agent-report-filter])
            {:keys [query remaining-query entity query-options]}
            (user-query->engine-query version query-map query-config warn-experimental)]

        (when log-queries
          ;; Log origin and AST of incoming query
          (let [{:keys [origin query]} query-map]
            (log/infof "PDBQuery:%s:%s"
                       query-id (-> (sorted-map :origin origin :ast query)
                                    json/generate-string))))

        (try
          (let [munge-fn (get-munge-fn entity version query-options url-prefix)
                stream-ctx (select-keys context [:log-queries :pretty-print :query-id
                                                 :query-deadline-ns
                                                 :query-monitor
                                                 :query-monitor-id])
                {:keys [status stream]} (body-stream scf-read-db
                                                     (coerce-from-json remaining-query)
                                                     entity version query-options
                                                     (munge-fn-hook munge-fn)
                                                     stream-ctx)
                {:keys [count error]} @status]
            (when error
              (throw error))
            (cond-> (http/json-response* stream)
              count (http/add-headers {:count count})))
          (catch JsonParseException ex
            (log/error ex (trs "Unparsable query: {0} {1} {2}" query-id query query-options))
            (http/error-response ex))
          (catch IllegalArgumentException ex ;; thrown by (at least) munge-fn
            (log/error ex (trs "Invalid query: {0} {1} {2}" query-id query query-options))
            (http/error-response ex))
          (catch PSQLException ex
            (when-not (= (.getSQLState ex) (jdbc/sql-state :invalid-regular-expression))
              (throw ex))
            (do
              (log/debug ex (trs "Invalid query regex: {0} {1} {2}" query-id query query-options))
              (http/error-response ex))))))))

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
