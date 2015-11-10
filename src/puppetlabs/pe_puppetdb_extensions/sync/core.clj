(ns puppetlabs.pe-puppetdb-extensions.sync.core
  (:import [org.joda.time Period])
  (:require [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as client]
            [puppetlabs.http.client.sync :as http]
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
            [puppetlabs.puppetdb.http.query :as http-q]))

;;; How to sync each entity

(def report-key (juxt :certname :producer_timestamp))

(defn clean-up-resource-event
  "The resource events we get back from a query have a lot of derived fields;
  only keep the ones we can re-submit."
  [resource-event]
  (select-keys resource-event
               [:status :timestamp :resource_type :resource_title :property
                :new_value :old_value :message :file :line :containment_path]))

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
    ;; records present on the remote system, buckted by hour, with a hash of the
    ;; contents of the bucket. This will be compared with the same query against
    ;; the local database and used to request the full record listing for only
    ;; the time spans which differ between the two machines.
    ;;
    ;; This path is relative to the 'query' endpoint. (/pdb/query/v4)
    :bucketed-summary-query-path "../../sync/v1/reports-summary"

    ;; On each side of the sync, we use this query to get
    ;; information about the identity of each record and a
    ;; hash of its content.
    :summary-query {:version :v4
                    :query ["extract" ["hash" "certname" "producer_timestamp"]
                            ["and" ["null?" "start_time" false]
                             include-inactive-nodes-criteria]]
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
                     :version 6}}

   {:entity :factsets
    :summary-query {:version :v4
                    :query ["extract" ["hash" "certname" "producer_timestamp"]
                            include-inactive-nodes-criteria]
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
                     :version 4}}

   {:entity :catalogs
    :summary-query {:version :v4
                    :query ["extract" ["hash" "certname" "producer_timestamp"]
                            include-inactive-nodes-criteria]
                    :order {:order_by [[:certname :ascending]]}}
    :record-id-fn :certname
    :record-fetch-key :certname
    :record-ordering-fn (juxt :producer_timestamp :hash)
    :clean-up-record-fn (fn clean-up-catalog [catalog]
                          (-> catalog
                              (utils/update-when [:edges] #(map clean-up-edge %))
                              (utils/update-when [:resources] #(map clean-up-resource %))))
    :submit-command {:command :replace-catalog
                     :version 7}}

   {:entity :nodes
    :summary-query {:version :v4
                    :query include-inactive-nodes-criteria
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
   (s/optional-key :ssl-cert) s/Str
   (s/optional-key :ssl-key) s/Str
   (s/optional-key :ssl-ca-cert) s/Str})

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

(defn-validated http-get
  "A wrapper around puppetlabs.http.client.sync/get which:

   - throws slingshot exceptions like clj-http does on error responses

   - takes a custom error formatter, `(fn [status body] ...)`, to provide a
     message which is written to the log on failure status codes "
  [remote-server :- remote-server-schema
   path :- s/Str
   opts :- {s/Any s/Any}
   error-message-fn]
  (try
    (let [full-url (url-on-remote-server remote-server path)
          full-opts (merge {:as :text}
                           (select-keys remote-server [:ssl-cert :ssl-key :ssl-ca-cert])
                           opts)
          _ (log/debugf "HTTP GET %s %s" (url-on-remote-server remote-server path) full-opts)
          response (http/get full-url full-opts)]
      (if (is-error-status? (:status response))
        (throw+ {:type ::remote-host-error :error-response response}
                (error-message-fn (:status response) (:body response)))
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

(defn http-get-for-expansion [url path key]
  (http-get url path {}
            (fn [status body]
              (format (str "Error getting URL %s, to expand record key %s. "
                           "Received HTTP status code %s with the error message '%s'")
                      (url-on-remote-server url path) key status body))))

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
                 [key (-> (http-get-for-expansion remote-server (get val :href) key)
                          :body
                          (json/parse-string true))])
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
    (with-open [body (:body (http-get remote-server path {:throw-entire-message true
                                                          :as :stream}
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

(defn normalize-time-stamp
  "Given a timestamp in some unknown format that clj-time understands, convert
  it to Joda time and to UTC."
  [ts]
  (-> ts
      to-date-time
      (t/to-time-zone (t/time-zone-for-offset 0))))

(defn timestamp-to-enumerable-hour
  "Convert a timestamp to an integer such that:
   - All timestamps within the same UTC clock hour map to the same integer.
   - Timestamps within adjacent UTC clock hours map to adjacent integers."
  [ts]
  (+ (* (- (t/year ts) 1970) 24 365)
     (* (.getDayOfYear ts) 24)
     (t/hour ts)))

(defn group-by-consecutive-hours
  "Given a seq of timestamps which are at the top of the hour (UTC), return a
  seq of [first-hour-timestamp last-hour-timestamp] ranges that represent groups
  of consecutive hours."
  [timestamps]
  (->> timestamps
       sort
       distinct
       (map normalize-time-stamp)
       (map (fn [ts] [(timestamp-to-enumerable-hour ts) ts]))
       (reduce (fn [[current-hour-num groups] [hour-num ts]]
                 [hour-num (if (and current-hour-num
                                    (= 1 (- hour-num current-hour-num)))
                             (update groups (dec (count groups))
                                     conj ts)
                             (conj groups [ts]))])
               [nil '[]])
       second))

(defn query-condition-for-buckets
  "Generate a condition clause for a PuppetDB query that limits the query to the
  hourly buckets given in timestamps."
  [timestamps]
  (let [conditions (->> timestamps
                        group-by-consecutive-hours
                        (map (fn [sorted-ts-group]
                               (let [start (first sorted-ts-group)
                                     end (.plusHours (last sorted-ts-group) 1)]
                                 ["and"
                                  [">=" "producer_timestamp" (.toString start)]
                                  ["<" "producer_timestamp" (.toString end)]]))))]
    (when (seq conditions)
      (apply vector "or" conditions))))

;;; Pull from remote instance

(defn query-record-and-transfer!
  "Query for a record where `record-fetch-key` (from `sync-config`) equals
  `record-fetch-val` and submit a command locally to copy that record, via
  `store-record-locally-fn`."
  [remote-server record submit-command-fn sync-config]
  (let [{:keys [entity record-fetch-key clean-up-record-fn
                submit-command]} sync-config
        entity-name (name entity)
        record-fetch-val (record-fetch-key record)
        store-record-locally-fn (partial submit-command-fn
                                         (:command submit-command)
                                         (:version submit-command))
        query ["and" ["=" (name record-fetch-key) record-fetch-val]
               include-inactive-nodes-criteria]
        qerr-msg (fn [status body]
                   (str "unable to ask " (:url remote-server) " for " entity-name
                        " using query " query "; received "
                        (pr-str {:status status :body body})))]
    (with-sync-events {:context (merge {:phase "record"
                                        :entity entity-name
                                        :remote (url-on-remote-server remote-server entity-name)
                                        :query query}
                                       (select-keys record [:certname :hash]))
                       :start [:debug "    syncing {entity} record ({certname} {hash}) from {remote}"]
                       :finished [:debug "    --> transferred {entity} record for query {query} via {remote} in {elapsed} ms"]
                       :error [:warn "    *** failed to sync {entity} record for query {query} via {remote} in {elapsed} ms"]}
      (-> (http-get remote-server entity-name
                    {:query-params {"query" (json/generate-string query)}}
                    qerr-msg)
          :body
          (json/parse-string true)
          first
          (collapse-and-download-collections remote-server)
          strip-timestamp-and-hash
          clean-up-record-fn
          store-record-locally-fn))))

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
                           (format "Error querying %s for record summaries (%s). Received HTTP status code %s with the error message '%s'"
                                   remote-server entity-name status body))]
    (-> (http-get remote-server entity-name
                  {:query-params {"query" (json/generate-string query)
                                  "order_by" (json/generate-string (order-by-clause-to-wire-format order))}
                   :as :stream
                   :throw-entire-message true}
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
      (let [have-bucketed-summary-query (boolean (:bucketed-summary-query-path sync-config))
            extra-query-condition (when have-bucketed-summary-query
                                    (let [remote (remote-bucketed-summary-query remote-server sync-config)
                                          local (bucketed-summary-query-fn entity)
                                          buckets-to-pull (diff-bucketed-summaries local remote)]
                                      (when (seq buckets-to-pull)
                                        (query-condition-for-buckets buckets-to-pull))))
            sync-config (update-in sync-config [:summary-query :query]
                                   (fn [q] (if extra-query-condition
                                             (http-q/add-criteria extra-query-condition q)
                                             q)))]
        ;; extra-query-condition will be nil if both bucketed summaries were the same
        (when (or extra-query-condition (not have-bucketed-summary-query))
          (with-open [summary-stream (remote-streamed-summary-query remote-server sync-config)]
            (let [remote-sync-data (map parse-time-fields
                                        (-> summary-stream
                                            clojure.java.io/reader
                                            (json/parse-stream true)))
                  remote-host-404? #(and (= ::remote-host-error (get % :type))
                                         (= 404 (get-in % [:error-response :status])))
                  {:keys [summary-query record-id-fn
                          record-ordering-fn]} sync-config
                  {:keys [version query order]} summary-query
                  incoming-records #(records-to-fetch record-id-fn record-ordering-fn
                                                      % remote-sync-data now node-ttl)
                  maybe-deactivate! #(set-local-deactivation-status! % submit-command-fn)
                  query-and-transfer! #(query-record-and-transfer!
                                        remote-server % submit-command-fn sync-config)]
              (query-fn version ["from" entity-name query] order
                        (fn [local-sync-data]
                          (doseq [record (incoming-records local-sync-data)]
                            (try+
                             (if (= entity :nodes)
                               (when (maybe-deactivate! record)
                                 (swap! stats update-in [:transferred] inc))
                               (do
                                 (query-and-transfer! record)
                                 (swap! stats update-in [:transferred] inc)))
                             (catch (remote-host-404? %) _
                               (swap! stats update-in [:failed] inc)))))))))
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
