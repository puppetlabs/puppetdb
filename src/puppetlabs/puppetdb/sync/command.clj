(ns puppetlabs.puppetdb.sync.command
  (:require [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; How to sync each entity

(def report-key (juxt :certname :hash))

(def sync-configs
  {:reports {:entity :reports
             ;; On each side of the sync, we use this query to get
             ;; information about the identity of each record and a
             ;; hash of its content.
             :record-hashes-query {:version :v4
                                   :query ["extract" ["hash" "certname" "start_time"]
                                           ["null?" "start_time" false]]
                                   :order {:order_by [[:certname :ascending] [:hash :ascending]]}}

             ;; The above query is done on each side of the sync; the
             ;; two are joined on the result of this function
             :record-id-fn report-key

             ;; If the same record exists on both sides, the result of
             ;; this function is used to find which is newer
             :record-ordering-fn (constantly 0) ; TODO: rename this, maybe to record-conflict-key-fn or something

             ;; When a record is out-of-date, the whole thing is
             ;; downloaded and then stored with this command
             :submit-command {:command :store-report
                              :version 5}}

   :factsets {:entity :factsets
              :record-hashes-query {:version :v4
                                    :query ["extract" ["hash" "certname" "producer_timestamp"]
                                            ["null?" "certname" false]] ; certname is never null, so this matches all factsets
                                    :order {:order_by [[:certname :ascending]]}}
              :record-id-fn :certname
              :record-ordering-fn (juxt :producer_timestamp :hash)
              :submit-command {:command :replace-facts
                               :version 4}}

   :catalogs {:entity :catalogs
              :record-hashes-query {:version :v4
                                    :query ["extract" ["hash" "certname" "producer_timestamp"]
                                            ["null?" "certname" false]] ; certname is never null, so this matches all factsets
                                    :order {:order_by [[:certname :ascending]]}}
              :record-id-fn :certname
              :record-ordering-fn (juxt :producer_timestamp :hash)
              :submit-command {:command :replace-catalog
                               :version 6}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull from remote instance

(defn query-remote
  "Queries a resource from `remote-url`/`entity`, where
  `entity-type` is a keyword like :reports, :factsets, etc. '"
  [remote-url entity query]
  (try+
   (let [{:keys [body]} (http/get (str remote-url "/" (name entity))
                                  {:query-params {:query (json/generate-string query)}
                                   :throw-exceptions true
                                   :throw-entire-message true})]
     (json/parse-string body))
   (catch :status {:keys [body status] :as response}
     (throw+ {:type ::remote-host-error
              :error-response response}
             (format "Error querying %s for %s with query %s. Received HTTP status code %s with the error message '%s'"
                     remote-url (name entity) query status body)))))

(defn strip-generated-fields
  "hash, receive_time, and timestamp are generated fields and need to
  be removed before submitting commands locally"
  [record]
  (dissoc record "hash" "receive_time" "timestamp"))

(defn query-record-and-transfer!
  "Queries for a record by `hash` from `remote`/`entity` and submits a command
  locally to copy that record, via store-record-locally-fn."
  [remote entity hash store-record-locally-fn]
  (-> (query-remote remote entity ["=" "hash" hash])
      first
      strip-generated-fields
      store-record-locally-fn))

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

(defn records-to-fetch
  "Compare two lazy seqs of records. Returns the list of records from
  `remote-records-seq` that `local-records-seq` is missing or out of date. Records are
  matched with each other via the result of `id-fn`, and compared for newness by
  running `clojure.core/compare` on the results of `ordering-fn`."
  [id-fn ordering-fn local-records-seq remote-records-seq]
  (->> (right-join-unique-sorted-seqs id-fn local-records-seq remote-records-seq)
       ;; Keep the records that either don't exist locally or are more up-to-date remotely
       (filter (fn [[local remote]]
                 (or (nil? local)
                     (neg? (compare (ordering-fn local) (ordering-fn remote))))))
       (map (fn [[_ remote]] remote))))

(defn maybe-update-in
  "Like update-in, except it won't throw if the path doesn't exist. "
  [x path f]
  (if (get-in x path)
    (update-in x path f)
    x))

(defn parse-time-fields
  "Convert time strings in a record to actual times. "
  [record]
  (-> record
      (maybe-update-in [:start_time] to-timestamp)
      (maybe-update-in [:producer_timestamp] to-timestamp)))

(defn query-result-handler
  "Build a result handler function in the form that query-fn expects."
  [handler-fn]
  (fn [f] (f handler-fn)))

(defn pull-records-from-remote!
  [query-fn submit-command-fn origin-host-path remote-sync-data sync-config]
  (let [{:keys [entity record-hashes-query record-id-fn record-ordering-fn submit-command]} sync-config
        {:keys [version query order]} record-hashes-query
        remote-sync-data (map parse-time-fields remote-sync-data)
        submit-this-command-fn (partial submit-command-fn (:command submit-command) (:version submit-command))]
    (query-fn entity version query order
              (query-result-handler (fn [local-sync-data]
                                      (doseq [x (records-to-fetch record-id-fn record-ordering-fn local-sync-data remote-sync-data)]
                                        (query-record-and-transfer! origin-host-path entity (:hash x) submit-this-command-fn)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Trigger sync

(defn generate-sync-message
  "Returns a function suitable for use in the streaming query
  function. Streams the sync data from `rows` to the passed in
  `piped-output-stream`. This needs to be run from a separate thread
  than where the associated PipedInputStream thread"
  [piped-out-stream origin-path entity]
  (fn [rows]
    (with-open [buffered-writer (-> piped-out-stream
                                    java.io.OutputStreamWriter.
                                    java.io.BufferedWriter.)]
      (json/generate-stream {:command "sync"
                             :version 1
                             :payload {:origin_host_path origin-path
                                       :entity entity
                                       :sync_data rows}}
                            buffered-writer))))

(defn send-record-hashes-to-remote
  "Queries the local instance using `query-fn` and constructs a sync
  message to send to `remote-path`. The remote instance will use
  `origin-path` to query for any missing or out-of-date entities."
  [query-fn origin-path remote-path sync-config]
  (let [{:keys [entity record-hashes-query]} sync-config
        {:keys [:version :query :order]} record-hashes-query]
   (let [piped-input-stream (java.io.PipedInputStream.)
         piped-output-stream (java.io.PipedOutputStream. piped-input-stream)
         command-fut (future (query-fn entity version query order
                                       (query-result-handler
                                         (generate-sync-message piped-output-stream origin-path entity))))
         result (http/post remote-path {:accept-encoding [:application/json]
                                        :content-type :application/json
                                        :body piped-input-stream})]
     (= 200 (:status result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn sync-from-remote
  "Entry point for syncing with another PuppetDB instance. Uses
  `query-fn` to query PuppetDB in process and `submit-command-fn` when
  new reports are found."
  [query-fn submit-command-fn {:keys [origin_host_path sync_data entity]}]
  (try+
   (pull-records-from-remote! query-fn submit-command-fn origin_host_path sync_data
                              (get sync-configs (keyword entity)))
   (catch [:type ::remote-host-error] _
     (let [{:keys [throwable message]} &throw-context]
       (log/error throwable (format "Sync to remote host failed due to: %s") message)))))

(defn sync-to-remote
  "Queries the local instance using `query-fn` and constructs a sync
  message to send to `remote-path`. The remote instance will use
  `origin-path` to query for the full report if it's out of date"
  [query-fn origin-path remote-path]
  (->> (vals sync-configs)
       (map #(send-record-hashes-to-remote query-fn origin-path remote-path %))
       doall
       (every? identity)))
