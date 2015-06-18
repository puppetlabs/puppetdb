(ns puppetlabs.pe-puppetdb-extensions.sync.core
  (:import [org.joda.time Period])
  (:require [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.cheshire :as json]
            [cheshire.core :as cheshire]
            [clj-time.core :as t]
            [clj-time.coerce :as tc :refer [to-timestamp from-sql-date]]
            [clj-time.format :as tf]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.pe-puppetdb-extensions.semlog :refer [maplog]]
            [puppetlabs.pe-puppetdb-extensions.sync.events :refer [with-sync-events]]
            [puppetlabs.puppetdb.time :refer [parse-period]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; How to sync each entity

(def report-key (juxt :certname :hash))

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
    ;; On each side of the sync, we use this query to get
    ;; information about the identity of each record and a
    ;; hash of its content.
    :record-hashes-query {:version :v4
                          :query ["extract" ["hash" "certname" "start_time"]
                                  ["and" ["null?" "start_time" false]
                                         include-inactive-nodes-criteria]]
                          :order {:order_by [[:certname :ascending] [:hash :ascending]]}}

    ;; The above query is done on each side of the sync; the
    ;; two are joined on the result of this function
    :record-id-fn report-key

    ;; When pulling a record from a remote machine, use the value at this key to
    ;; identify it; This shoud be part of the result you get with
    ;; `record-hashes-query` above.
    :record-fetch-key :hash

    ;; If the same record exists on both sides, the result of
    ;; this function is used to find which is newer
    :record-ordering-fn (constantly 0) ; TODO: rename this, maybe to record-conflict-key-fn or something

    :clean-up-record-fn (fn clean-up-report [report]
                          (utils/update-when report [:resource_events] #(map clean-up-resource-event %)))

    ;; When a record is out-of-date, the whole thing is
    ;; downloaded and then stored with this command
    :submit-command {:command :store-report
                     :version 5}}

   {:entity :factsets
    :record-hashes-query {:version :v4
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
    :record-hashes-query {:version :v4
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
                     :version 6}}

   {:entity :nodes
    :record-hashes-query {:version :v4
                          :query include-inactive-nodes-criteria
                          :order {:order_by [[:certname :ascending]]}}
    :record-id-fn :certname
    :record-fetch-key :certname
    :record-ordering-fn :deactivated
    :clean-up-record-fn identity}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utils

(defn http-get
  "A wrapper around clj-http.client/get which takes a custom error formatter,
  `(fn [status body] ...)`, to provide a message which is written to the log on
  failure status codes. '"
  [url opts error-message-fn]
  (try+
   (log/debugf "HTTP GET %s %s" url opts)
   (client/get url
               (merge {:throw-exceptions true :throw-entire-message true} opts))
   (catch :status  {:keys [body status] :as response}
     (throw+ {:type ::remote-host-error :error-response response}
             (error-message-fn status body)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

(defn url-with-path
  "Return a url with path `path` based on the host, scheme, etc. from
  `original-url-str`."
  [path original-url-str]
  (let [uri (java.net.URI. original-url-str)
        new-uri (java.net.URI. (.getScheme uri)
                               (.getUserInfo uri)
                               (.getHost uri)
                               (.getPort uri)
                               path nil nil)]
    (str new-uri)))

(defn get-url-for-expansion [url key]
  (http-get url {} (fn [status body]
                     (format "Error getting URL %s, to expand record key %s. Received HTTP status code %s with the error message '%s'"
                             url key status body))))

(defn collapse-and-download-collections
  "Look for values in `record` which are maps with `data` and `href`
  keys. Transform these values to just be the contents of `data`, which is the
  form needed when submitting a command. If `data` is absent, use the content at
  `href` to fill it out."
  [record remote-url]
  (into {} (for [[key val] record]
             (if (and (map? val) (contains? val :href))
               (if (contains? val :data)
                 [key (get val :data)]
                 [key (-> (get val :href)
                          (url-with-path remote-url)
                          (get-url-for-expansion key)
                          :body
                          (json/parse-string true))])
               [key val]))))

(defn strip-timestamp-and-hash
  "`hash`, `receive_time`, and `timestamp` are generated fields and need to be
  removed before submitting commands locally"
  [record]
  (dissoc record :hash :receive_time :timestamp))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull from remote instance

(defn remote-query-url [remote-url entity query]
  (let [uri (java.net.URI. remote-url)]
    (str (java.net.URI. (.getScheme uri)
                        (.getAuthority uri)
                        (str (.getPath uri) "/" (name entity))
                        (str "query=" (json/generate-string query))
                        (.getFragment uri)))))

(defn query-remote
  "Queries url and returns the body after parsing it as JSON."
  [url error-message-fn]
  (let [{:keys [status body]} (http-get url {} error-message-fn)]
    (json/parse-string body true)))

(defn query-record-and-transfer!
  "Query for a record where `record-fetch-key` (from `sync-config`) equals
  `record-fetch-val` and submit a command locally to copy that record, via
  `store-record-locally-fn`."
  [remote-url record submit-command-fn sync-config]
  (let [{:keys [entity record-fetch-key clean-up-record-fn
                submit-command]} sync-config
        entity-name (name entity)
        record-fetch-val (record-fetch-key record)
        store-record-locally-fn (partial submit-command-fn
                                         (:command submit-command)
                                         (:version submit-command))
        query ["and" ["=" (name record-fetch-key) record-fetch-val]
               include-inactive-nodes-criteria]
        query-url (remote-query-url remote-url entity query)
        qerr-msg (fn [status body]
                   (str "unable to ask" remote-url "for" entity-name
                        "using query" query "; received"
                        (pr-str {:status status :body body})))]
    (with-sync-events {:type :record
                       :context {:entity entity-name
                                 :remote query-url
                                 :query query}
                       :start [:debug "    syncing {entity} record ({certname} {hash}) from {remote}"]
                       :finished [:debug "    --> transferred {entity} record for query {query} via {remote} in {elapsed} ms"]
                       :error [:warn "    *** failed to sync {entity} record for query {query} via {remote} in {elapsed} ms"]}
      (-> (query-remote query-url qerr-msg)
          first
          (collapse-and-download-collections remote-url)
          strip-timestamp-and-hash
          clean-up-record-fn
          store-record-locally-fn))))

(defn set-local-deactivation-status!
  [remote-url
   entity
   {:keys [certname deactivated] :as remote-record}
   submit-command-fn]
  ;; deactivated never goes false (null) by itself; one of the other entities
  ;; will change, reactivating it as a side effect
  (when deactivated
    (let [log-ctx #(hash-map :event "finished-sync-deactivation"
                             :entity (name entity)
                             :certname certname
                             :ok (boolean %))]
      (try
        (submit-command-fn :deactivate-node 3
                           {:certname certname :producer_timestamp deactivated})
        (maplog [:sync :debug] (log-ctx true) "deactivated %s" certname)
        (catch Exception ex
          (maplog [:sync :warn] ex (log-ctx false) "failed to deactivate {certname}")
          (throw ex))))))

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
      (utils/update-when [:producer_timestamp] to-timestamp)))

(defn streamed-summary-query
  "Perform the summary query at `remote-url`, as specified in
  `sync-config`. Returns a stream which must be closed."
  [remote-url sync-config]
  (let [{:keys [entity record-hashes-query ]} sync-config
        {:keys [version query order]} record-hashes-query
        url (str remote-url "/" (name entity))
        error-message-fn (fn [status body]
                           (format "Error querying %s for record summaries (%s). Received HTTP status code %s with the error message '%s'"
                                   remote-url (name entity) status body))]
        (-> url
            (http-get {:query-params {:query (json/generate-string query)
                                      :order_by (json/generate-string (order-by-clause-to-wire-format order))}
                       :as :stream
                       :throw-entire-message true}
                      error-message-fn)
            :body)))

(defn pull-records-from-remote!
  "Query `remote-url` using the query api and the local PDB using `query-fn`,
  using the entity and comparison information in `sync-config` (see
  comments above). If the remote record is newer than the local one or
  it doesn't exist locally, download it over http and place it in the
  queue with `submit-command-fn`.  Return false if any records
  failed."
  [query-fn submit-command-fn remote-url sync-config now node-ttl]
  (let [entity (:entity sync-config)
        entity-name (name entity)
        stats (atom {:transferred 0 :failed 0})]
    (with-sync-events {:type :entity
                       :context {:entity entity-name
                                 :remote remote-url
                                 :transferred #(:transferred @stats)
                                 :failed #(:failed @stats)}
                       :start [:info "  syncing {entity} from {remote}"]
                       :finished [:info "  --> transferred {entity} ({transferred}) from {remote} in {elapsed} ms"]
                       :error [:warn (str "  *** transferred {entity} ({transferred}) from {remote};"
                                          " stopped after {failed} failures in {elapsed} ms")]}
      (with-open [summary-stream (streamed-summary-query remote-url sync-config)]
        (let [remote-sync-data (map parse-time-fields
                                    (-> summary-stream
                                        clojure.java.io/reader
                                        (json/parse-stream true)))
              remote-host-404? #(and (= ::remote-host-error (get % :type))
                                     (= 404 (get-in % [:error-response :status])))
              {:keys [record-hashes-query record-id-fn
                      record-ordering-fn]} sync-config
              {:keys [version query order]} record-hashes-query
              incoming-records #(records-to-fetch record-id-fn record-ordering-fn
                                                  % remote-sync-data now node-ttl)
              deactivate! #(set-local-deactivation-status! remote-url entity %
                                                           submit-command-fn)
              query-and-transfer! #(query-record-and-transfer!
                                    remote-url % submit-command-fn sync-config)]
          (query-fn entity version query order
                    (fn [local-sync-data]
                      (doseq [record (incoming-records local-sync-data)]
                        (try+
                          (if (= entity :nodes)
                            (deactivate! record)
                            (query-and-transfer! record))
                          (swap! stats update-in [:transferred] inc)
                          (catch (remote-host-404? %) _
                            (swap! stats update-in [:failed] inc))))))
          (zero? (:failed @stats)))))))

(defmacro wrap-with-logging [f level message]
  `(fn [& args#]
     (log/logp ~level (str ~message " " args#))
     (apply ~f args#)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn sync-from-remote!
  "Entry point for syncing with another PuppetDB instance. Uses
  `query-fn` to query PuppetDB in process and `submit-command-fn` when
  new data is found."
  [query-fn submit-command-fn remote-url node-ttl]
  (let [submit-command-fn (wrap-with-logging submit-command-fn
                                             :debug "Submitting command")
        now (t/now)]
    (with-sync-events {:type :sync
                       :context {:remote remote-url}
                       :start [:info "syncing with {remote}"]
                       :finished [:info "--> synced with {remote}"]
                       :error [:warn "*** trouble syncing with {remote}"]}

      (doseq [sync-config sync-configs]
        (pull-records-from-remote! query-fn
                                   submit-command-fn
                                   remote-url
                                   sync-config
                                   now
                                   node-ttl)))))
