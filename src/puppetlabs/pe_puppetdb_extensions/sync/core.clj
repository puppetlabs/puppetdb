(ns puppetlabs.pe-puppetdb-extensions.sync.core
  (:import [org.joda.time Period])
  (:require [clojure.java.jdbc :as sql]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as client]
            [puppetlabs.http.client.common :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [cheshire.core :as cheshire]
            [clj-time.core :as t]
            [clj-time.coerce :as tc :refer [to-timestamp from-sql-date to-date-time]]
            [clj-time.format :as tf]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.structured-logging.core :refer [maplog]]
            [puppetlabs.pe-puppetdb-extensions.sync.events :as events
             :refer [with-sync-events]]
            [puppetlabs.puppetdb.scf.storage
             :refer [node-deactivated-time have-newer-record-for-certname?]]
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [schema.core :as s]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary :refer [pdb-query-condition-for-buckets]]))

;;; How to sync each entity

(def report-key (juxt :certname :producer_timestamp))

(defn clean-up-edge [edge]
  {:source {:type (:source_type edge)
            :title (:source_title edge)}
   :target {:type (:target_type edge)
            :title (:target_title edge)}
   :relationship (:relationship edge)})

(defn remove-nil-field [resource key]
  (if (nil? (get resource key))
    (dissoc resource key)
    resource))

(defn clean-up-resource [resource]
  (-> resource
      (select-keys [:type :title :aliases :exported
                    :file :line :tags :parameters])
      (remove-nil-field :line)
      (remove-nil-field :file)))

(def include-inactive-nodes-criteria
  ["or" ["=" ["node" "active"] true]
        ["=" ["node" "active"] false]])

(def sync-configs
  [{:entity :reports

    ;; The first step of entity sync is to request a short summary of the
    ;; records present on the remote system, bucketed by hour, with a hash of
    ;; the contents of the bucket. This will be compared with the same query
    ;; against the local database and used to request the full record listing
    ;; for only the time spans which differ between the two machines.
    ;;
    ;; This path is relative to the 'query' endpoint. (/pdb/query/v4)
    :bucketed-summary-query-path "../../sync/v1/reports-summary"

    ;; On each side of the sync, we use this query to get
    ;; information about the identity of each record and a
    ;; hash of its content.
    :summary-query {:version :v4
                    :query ["from" "reports"
                            ["extract" ["hash" "certname" "producer_timestamp"]
                             ["and" ["null?" "start_time" false]
                              include-inactive-nodes-criteria]]]
                    :order {:order_by [[:certname :ascending] [:producer_timestamp :ascending]]}}

    ;; The above query is done on each side of the sync; the
    ;; two are joined on the result of this function
    :record-id-fn report-key

    ;; When pulling a record from a remote machine, use the value at this key to
    ;; identify it; This should be part of the result you get with
    ;; `summary-query` above.
    :record-fetch-key :hash

    ;; If the same record exists on both sides, the result of
    ;; this function is used to find which is newer
    :record-ordering-fn (constantly 0) ; TODO: rename this, maybe to record-conflict-key-fn or something

    :clean-up-record-fn (fn clean-up-report [report]
                          (dissoc report :resource_events))

    ;; When a record is out-of-date, the whole thing is
    ;; downloaded and then stored with this command
    :submit-command {:command :store-report
                     :version 8}}

   {:entity :factsets
    :summary-query {:version :v4
                    :query ["from" "factsets"
                            ["extract" ["hash" "certname" "producer_timestamp"]
                             include-inactive-nodes-criteria]]
                    :order {:order_by [[:certname :ascending]]}}
    :record-id-fn :certname
    :record-fetch-key :certname
    :record-ordering-fn (juxt :producer_timestamp :hash)
    :clean-up-record-fn (fn clean-up-factset [factset]
                          (-> factset
                              (dissoc :facts)
                              (assoc :values (into {} (for [{:keys [name value]} (:facts factset)]
                                                        [name value])))))
    :submit-command {:command :replace-facts
                     :version 5}}

   {:entity :historical_catalogs
    ;; Bucketed summary queries are disabled for catalogs, for now, since we're
    ;; only keeping a very short history. We can enable this once we solve the storage problems
    ;; and need to deal with more history.
    ;; :bucketed-summary-query-path "../../sync/v1/catalogs-summary"
    :summary-query {:version :v4
                    :query ["from" "historical_catalogs"
                            ["extract" ["transaction_uuid" "producer_timestamp"]
                             include-inactive-nodes-criteria]]
                    :order {:order_by [[:transaction_uuid :ascending]]}}
    :record-id-fn :transaction_uuid
    :record-fetch-key :transaction_uuid
    :record-ordering-fn :transaction_uuid
    :clean-up-record-fn (fn clean-up-catalog [catalog]
                          (-> catalog
                              (utils/update-when [:edges] #(map clean-up-edge %))
                              (utils/update-when [:resources] #(map clean-up-resource %))))
    :submit-command {:command :replace-catalog
                     :version 9}}

   {:entity :nodes
    :summary-query {:version :v4
                    :query ["from" "nodes"
                            include-inactive-nodes-criteria]
                    :order {:order_by [[:certname :ascending]]}}
    :record-id-fn :certname
    :record-fetch-key :certname
    :record-ordering-fn :deactivated
    :clean-up-record-fn identity}])

;;; Utils

(defn is-error-status? [status-code]
  (>= status-code 400))

(defn without-trailing-slash [^String url]
  (if (.endsWith url "/")
    (subs url 0 (dec (count url)))
    url))

(defn with-trailing-slash [^String url]
  (if-not (.endsWith url "/")
    (str url "/")
    url))

(defn uri-with-trailing-slash [url-str]
  (java.net.URI. (with-trailing-slash url-str)))

(defn query-string [url]
  (let [^java.net.URI uri (uri-with-trailing-slash url)]
    (.getQuery uri)))

(def remote-server-schema
  {:url s/Str
   :client (s/protocol http/HTTPClient)})

(defn-validated url-on-remote-server :- s/Str
  [{:keys [url]} :- remote-server-schema
   path :- s/Str]
  (if (query-string url)
    (do
      (assert (or (not path)
                  (empty? path))
              "If url has a query string, path must be null or empty")
      url)
    (let [^java.net.URI uri (uri-with-trailing-slash url)]
      (without-trailing-slash (str (.resolve uri path))))))


(defn-validated http-request
  "A wrapper around puppetlabs.http.client.sync which:

   - throws slingshot exceptions like clj-http does on error responses

   - takes a custom error formatter, `(fn [status body] ...)`, to provide a
     message which is written to the log on failure status codes "
  [method :- (s/enum :get :post)
   remote-server :- remote-server-schema
   path :- s/Str
   opts :- {s/Any s/Any}
   error-message-fn]
  (try
    (let [full-url (url-on-remote-server remote-server path)
          request-opts (merge {:as :text} opts)
          _ (log/debug (i18n/trs "HTTP {0} {1} {2}"
                                 (clojure.string/upper-case (name method))
                                 (url-on-remote-server remote-server path)
                                 request-opts))
          request-fn (case method
                       :get http/get
                       :post http/post)
          response (request-fn (:client remote-server) full-url request-opts)]
      (if (is-error-status? (:status response))
        (let [response (update response :body #(if (string? %) % (slurp %)))]
          (throw+ {:type ::remote-host-error :error-response response}
                  (error-message-fn (:status response) (:body response))))
        response))
    (catch Exception e
      (events/failed-request!)
      (throw e))))

;;; Data format transformations

(defn order-by-clause-to-wire-format
  "Given an internal order_by clause, of the form {:order_by [[:column :ascending]]},
   convert it to the format we use on the wire: [{:field :column, :order :asc}]."
  [internal-order-by-clause]
  (map (fn [[key order]]
         {:field key
          :order (case order
                   :ascending :asc
                   :descending :desc)})
       (:order_by internal-order-by-clause)))

(defn collapse-and-download-collections
  "Look for values in `record` which are maps with `data` and `href`
  keys. Transform these values to just be the contents of `data`, which is the
  form needed when submitting a command. If `data` is absent, use the content at
  `href` to fill it out."
  [record remote-server]
  (into {} (for [[key val] record]
             (if (and (map? val) (contains? val :href))
               (if (contains? val :data)
                 [key (get val :data)]
                 (throw (Exception. (str "Can't process a record with :href but no "
                                         ":data. Are you trying to sync with a "
                                         "PuppetDB running on hsqldb?"))))
               [key val]))))

(defn strip-timestamp-and-hash
  "`hash`, `receive_time`, and `timestamp` are generated fields and need to be
  removed before submitting commands locally"
  [record]
  (dissoc record :hash :receive_time :timestamp))

;;; Join functions

(defn outer-join-unique-sorted-seqs
  "Outer join two seqs, `xs` and `ys`, that are sorted and unique under
  `id-fn`. Returns a lazy seq of vectors `([x1 y1] [x2 y2] [x3 nil] [nil y4] ...)`"
  [id-fn xs ys]
  (lazy-seq
   (when (or (seq xs) (seq ys))
     (let [x (first xs)
           y (first ys)
           id-comparison (and x y (compare (id-fn x) (id-fn y)))
           result (cond
                    (nil? y) [x nil]              ; xs is empty
                    (nil? x) [nil y]              ; ys is empty
                    (neg? id-comparison) [x nil]  ; not equal, and x goes first
                    (zero? id-comparison) [x y]
                    (pos? id-comparison) [nil y]) ; not equal, and y goes first
           [result-x result-y] result]
       (cons result (outer-join-unique-sorted-seqs id-fn
                                                   (if result-x (rest xs) xs)
                                                   (if result-y (rest ys) ys)))))))

(defn right-join-unique-sorted-seqs [id-fn xs ys]
  (->> (outer-join-unique-sorted-seqs id-fn xs ys)
       (filter second)))

;;; Bucketed summary

(defn remote-bucketed-summary-query
  "Perform the bucketed summary query at `remote-server`, as specified in
  `sync-config`. "
  [remote-server sync-config]
  (let [{path :bucketed-summary-query-path entity :entity} sync-config
        error-message-fn (fn [status body]
                           (format (str "Error performing bucketed summary query at "
                                        "%s. Received HTTP status code %s with the "
                                        "error message '%s'")
                                   (url-on-remote-server remote-server path) status (slurp body)))]
    (with-open [body (:body (http-request :get remote-server path {:as :stream}
                                          error-message-fn))
                body-reader (clojure.java.io/reader body)]
      (ks/mapkeys to-date-time
                  (json/parse-stream body-reader)))))

(defn diff-bucketed-summaries
  "Given two bucketed summary maps of the form {bucket-start-timestamp content-hash},
  return a seq of timestamps whose hashes differ or which are only present in
  remote."
  [local remote]
  (let [local (sort-by key local)
        remote (sort-by key remote)]
    (->> (right-join-unique-sorted-seqs key local remote)
         (keep (fn [[local [bucket-timestamp remote-hash]]]
                 (let [local-hash (and local (second local))]
                   (when (not= local-hash remote-hash)
                     bucket-timestamp)))))))

(defn run-and-compare-bucketed-summary-queries [sync-config remote-server bucketed-summary-query-fn]
  (when (:bucketed-summary-query-path sync-config)
    (let [remote (remote-bucketed-summary-query remote-server sync-config)
          local (bucketed-summary-query-fn (:entity sync-config))]
      (diff-bucketed-summaries local remote))))

(defn update-summary-query-with-bucket-timespans [sync-config buckets-which-differ]
  (if buckets-which-differ
    (let [extra-query-condition (pdb-query-condition-for-buckets buckets-which-differ)]
      (update-in sync-config [:summary-query :query]
                 (fn [q] (if extra-query-condition
                           (http-q/add-criteria extra-query-condition q)
                           q))))
    sync-config))

;;; Pull from remote instance

(defn ingest-record-data [record-data remote-server clean-up-record-fn store-record-locally-fn]
  (-> record-data
      (collapse-and-download-collections remote-server)
      strip-timestamp-and-hash
      clean-up-record-fn
      store-record-locally-fn))

(defn transfer-batch
  "Query for a record where `record-fetch-key` (from `sync-config`) equals
  `record-fetch-val` and submit a command locally to copy that record, via
  `store-record-locally-fn`."
  [remote-server record-seq submit-command-fn sync-config]
  (let [{:keys [entity record-fetch-key clean-up-record-fn
                submit-command]} sync-config
        entity-name (name entity)
        record-fetch-vals (map record-fetch-key record-seq)
        store-record-locally-fn (partial submit-command-fn
                                         (:command submit-command)
                                         (:version submit-command))
        ingest-record-data #(ingest-record-data % remote-server clean-up-record-fn store-record-locally-fn)
        query ["from" entity
               ["and"
                ["in" (name record-fetch-key) ["array" (vec record-fetch-vals)]]
                include-inactive-nodes-criteria]]
        qerr-msg (fn [status body]
                   (str "unable to request " entity-name " from " (:url remote-server)
                        " using query " query "; received "
                        (pr-str {:status status :body body})))]
    (with-sync-events {:context (merge {:phase "record"
                                        :entity entity-name
                                        :remote (url-on-remote-server remote-server entity-name)
                                        :query query})
                       :start [:debug "    syncing {entity} record ({certname} {hash}) from {remote}"]
                       :finished [:debug "    --> transferred {entity} record for query {query} via {remote} in {elapsed} ms"]
                       :error [:warn "    *** failed to sync {entity} record for query {query} via {remote} in {elapsed} ms"]}
      (with-open [body-stream (-> (http-request :post remote-server ""
                                                {:as :stream
                                                 :body (json/generate-string {:query query})
                                                 :headers {"Content-Type" "application/json"}}
                                                qerr-msg)
                                  :body)
                  body-reader (clojure.java.io/reader body-stream)]
        (let [records-transferred (->> (json/parse-stream body-reader true)
                                       (map ingest-record-data)
                                       count)]
          {:transferred records-transferred
           :failed (- (count record-seq) records-transferred)})))))

(defn- need-local-deactivation?
  "Returns a truthy value indicating whether a local deactivation is
  required."
  [certname new-deactivation-time]
  (when new-deactivation-time
    (let [local-deactivated (node-deactivated-time certname)]
      (and (not (have-newer-record-for-certname? certname new-deactivation-time))
           (or (not local-deactivated)
               (t/before? (from-sql-date local-deactivated)
                          (from-sql-date new-deactivation-time)))))))

(defn- set-local-deactivation-status!
  "Returns a truthy value indicating whether a local deactivation was
  required."
  [{:keys [certname deactivated] :as remote-record}
   submit-command-fn]
  ;; deactivated never goes false (null) by itself; one of the other entities
  ;; will change, reactivating it as a side effect
  (when (need-local-deactivation? certname deactivated)
    (with-sync-events {:context {:phase "deactivate"
                                 :certname certname
                                 :producer_timestamp deactivated}
                       :start [:debug "    deactivating {certname} as of {producer_timestamp}"]
                       :finished [:debug "    deactivated {certname}"]
                       :error [:error  "    error deactivating {certname}"]}
      (submit-command-fn :deactivate-node 3
                         {:certname certname :producer_timestamp deactivated})
      true)))

(defn would-be-expired-locally?
  "If this record existed locally, would it be expired according to our local
  node-ttl settings?"
  [record now node-ttl]
  (when-not (= Period/ZERO node-ttl)
    (some-> record
            :producer_timestamp
            tc/from-date
            (t/before? (t/minus now node-ttl)))))

(defn records-to-fetch
  "Compare two lazy seqs of records. Returns the list of records from
  `remote-records-seq` that `local-records-seq` is missing or out of date. Records are
  matched with each other via the result of `id-fn`, and compared for newness by
  running `clojure.core/compare` on the results of `ordering-fn`."
  [id-fn ordering-fn local-records-seq remote-records-seq now node-ttl]
  (->> (right-join-unique-sorted-seqs id-fn local-records-seq remote-records-seq)
       ;; Keep the records that either don't exist locally or are more up-to-date remotely
       (filter (fn [[local remote]]
                 (or (nil? local)
                     (neg? (compare (ordering-fn local) (ordering-fn remote))))))
       (map (fn [[_ remote]] remote))
       (remove #(would-be-expired-locally? % now node-ttl))))

(defn parse-time-fields
  "Convert time strings in a record to actual times. "
  [record]
  (-> record
      (utils/update-when [:start_time] to-timestamp)
      (utils/update-when [:producer_timestamp] to-timestamp)
      (utils/update-when [:deactivated] to-timestamp)))

(defn remote-streamed-summary-query
  "Perform the summary query at `remote-server`, as specified in
  `sync-config`. Returns a stream which must be closed."
  [remote-server sync-config]
  (let [{:keys [entity summary-query]} sync-config
        {:keys [version query order]} summary-query
        entity-name (name entity)
        error-message-fn (fn [status body]
                           (format (str "Error querying %s for record summaries (%s)."
                                        "Received HTTP status code %s with the error message '%s'")
                                   remote-server entity-name status body))]
    (-> (http-request :post remote-server ""
                      {:as :stream
                       :body (json/generate-string
                              {:query query
                               :order_by (order-by-clause-to-wire-format order)})}
                      error-message-fn)
        :body)))

(defn pull-records-from-remote!
  "Query `remote-server` using the query api and the local PDB using `query-fn`,
  using the entity and comparison information in `sync-config` (see
  comments above). If the remote record is newer than the local one or
  it doesn't exist locally, download it over http and place it in the
  queue with `submit-command-fn`.  Return false if any records
  failed."
  [query-fn bucketed-summary-query-fn submit-command-fn remote-server sync-config now node-ttl]
  (let [entity (:entity sync-config)
        entity-name (name entity)
        stats (atom {:transferred 0 :failed 0})]
    (with-sync-events {:context {:phase "entity"
                                 :entity entity-name
                                 :remote (url-on-remote-server remote-server entity-name)
                                 :transferred #(:transferred @stats)
                                 :failed #(:failed @stats)}
                       :timer-key (juxt :phase :entity)
                       :start [:info "  syncing {entity} from {remote}"]
                       :finished [:info "  --> transferred {entity} ({transferred}) from {remote} in {elapsed} ms"]
                       :error [:warn (str "  *** transferred {entity} ({transferred}) from {remote};"
                                          " stopped after {failed} failures in {elapsed} ms")]}
      (let [buckets-which-differ (run-and-compare-bucketed-summary-queries sync-config remote-server bucketed-summary-query-fn)
            sync-config (update-summary-query-with-bucket-timespans sync-config buckets-which-differ)
            need-to-do-detailed-summary-query (if (:bucketed-summary-query-path sync-config)
                                                ;; if we did a bucketed summary  query, we can
                                                ;; quit outright if there were no differences
                                                (not= buckets-which-differ [])
                                                ;; if there was no bucketed summary, always
                                                ;; do the detailed summary
                                                true)]
        (when need-to-do-detailed-summary-query
          (with-open [remote-summary-stream (remote-streamed-summary-query remote-server sync-config)
                      remote-summary-reader (-> remote-summary-stream clojure.java.io/reader)]
            (let [{:keys [summary-query record-id-fn
                          record-ordering-fn]} sync-config
                  {:keys [version query order]} summary-query
                  incoming-records (fn [local-summary-seq]
                                     (records-to-fetch record-id-fn record-ordering-fn
                                                       local-summary-seq
                                                       (map parse-time-fields (json/parse-stream remote-summary-reader true))
                                                       now node-ttl))
                  maybe-deactivate #(set-local-deactivation-status! % submit-command-fn)
                  transfer-batch #(transfer-batch remote-server % submit-command-fn sync-config)]
              (query-fn version query order
                        ;; TODO: we're retaining the seq head here; need to change the API to pass a thunk instead
                        (fn [local-summary-seq]
                          ;; transfer records in batches of 5000, to avoid per-request overhead
                          (doseq [batch (partition-all 5000 (incoming-records local-summary-seq))]
                            (if (= entity :nodes)
                              (doseq [record batch]
                                (when (maybe-deactivate record)
                                  (swap! stats update :transferred + (count batch))))
                              (let [batch-transfer-stats (transfer-batch batch)]
                                (swap! stats (partial merge-with +) batch-transfer-stats)))))))))
        @stats))))

(defn wrap-submit-command-fn
  "Wrap the given submit-command-fn to first generate a uuid for the command,
  write it to 'submitted-commands-chan', then finally call the wrapped fn."
  [submit-command-fn submitted-commands-chan]
  (fn [command version payload]
    (let [uuid (ks/uuid)]
      (maplog [:sync :debug] {:command command :version version :uuid uuid}
              "Submitting {command} command")
      (when submitted-commands-chan
        (async/>!! submitted-commands-chan {:id uuid}))
      (submit-command-fn command version payload uuid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn-validated sync-from-remote!
  "Entry point for syncing with another PuppetDB instance. Uses
  `query-fn` to query PuppetDB in process and `submit-command-fn` when
  new data is found."
  ([query-fn
    bucketed-summary-query-fn
    submit-command-fn
    remote-server :- remote-server-schema
    node-ttl :- Period]
   (sync-from-remote! query-fn
                      bucketed-summary-query-fn
                      submit-command-fn
                      remote-server
                      node-ttl
                      nil))
  ([query-fn
    bucketed-summary-query-fn
    submit-command-fn
    remote-server :- remote-server-schema
    node-ttl :- Period
    submitted-commands-chan]
   (try
     (let [submit-command-fn (wrap-submit-command-fn submit-command-fn submitted-commands-chan)
           now (t/now)]
       (with-sync-events {:context {:phase "sync"
                                    :remote (url-on-remote-server remote-server "")}
                          :start [:info "syncing with {remote}"]
                          :finished [:info "--> synced with {remote}"]
                          :error [:warn "*** trouble syncing with {remote}"]}
         (let [result (apply merge-with +
                             (for [sync-config sync-configs]
                               (pull-records-from-remote! query-fn
                                                          bucketed-summary-query-fn
                                                          submit-command-fn
                                                          remote-server
                                                          sync-config
                                                          now
                                                          node-ttl)))]
           (events/successful-sync!)
           result)))
     (catch Exception e
       (events/failed-sync!)
       (throw e)))))
