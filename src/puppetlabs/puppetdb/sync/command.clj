(ns puppetlabs.puppetdb.sync.command
  (:require [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull from remote instance

(defn query-remote
  "Queries a resource from `remote-url`/`entity`, where
  `entity-type` is a keyword like :reports, :factsets, etc. '"
  [remote-url entity query]
  (let [encoded-query (url-encode (json/generate-string query))
        response (http/get (str remote-url "/" (name entity) "?query=" encoded-query))
        {:keys [status body]} response]
    (if (= status 200)
      (json/parse-string body)
      [])))

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
  [x path fn]
  (if (get-in x path)
    (update-in x path fn)
    x))

(defn parse-time-fields
  "Convert time strings in a record to actual times. "
  [record]
  (-> record
      (maybe-update-in [:start_time] to-timestamp)
      (maybe-update-in [:producer_timestamp] to-timestamp)))

(defn sync-records-with-remote
  "Returns a function that is useful in the query streaming query
  callback function in PuppetDB. This function will compare the state
  of the local (in-process) instance with the remote (`remote-sync-data`)
  instance"
  [remote-sync-data origin-host-path entity record-id-fn record-ordering-fn submit-specific-command-fn]
  (fn [local-sync-data]
    (let [remote-sync-data (map parse-time-fields remote-sync-data)]
      (doseq [x (records-to-fetch record-id-fn record-ordering-fn local-sync-data remote-sync-data)]
        (query-record-and-transfer! origin-host-path entity (:hash x) submit-specific-command-fn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull entities from remote (sync command handlers)

(defmulti pull-records-from-remote (fn [query-fn submit-command-fn command-payload]
                                     (keyword (:entity command-payload))))

(defmethod pull-records-from-remote :reports
  [query-fn submit-command-fn {:keys [origin_host_path sync_data]}]
  (query-fn :reports :v4 ["extract" ["hash" "certname" "start_time"]
                          ["null?" "start_time" false]] ; start_time is never null, so this matches all reports
            {:order_by [[:certname :ascending] [:hash :ascending]]}
            (fn [f]
              (f (sync-records-with-remote sync_data
                                           origin_host_path :reports
                                           (juxt :certname :hash) (constantly 0)
                                           (partial submit-command-fn :store-report 5))))))

(defmethod pull-records-from-remote :factsets
  [query-fn submit-command-fn {:keys [origin_host_path sync_data]}]
  (query-fn :factsets :v4 ["extract" ["hash" "certname" "producer_timestamp"]
                           ["null?" "certname" false]] ; certname is never null, so this matches all factsets
            {:order_by [[:certname :ascending]]}
            (fn [f]
              (f (sync-records-with-remote sync_data
                                           origin_host_path :factsets
                                           :certname (juxt :producer_timestamp :hash)
                                           (partial submit-command-fn :replace-facts 4))))) )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Trigger sync

(defn generate-sync-message
  "Returns a function suitable for use in the streaming query
  function. Streams the sync data from `rows` to the passed in
  `piped-output-stream. This needs to be run from a separate thread
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
  [query-fn origin-path remote-path api-version entity query order-clause]
  (let [piped-input-stream (java.io.PipedInputStream.)
        piped-output-stream (java.io.PipedOutputStream. piped-input-stream)
        command-fut (future (query-fn entity api-version query order-clause
                                      (fn [f]
                                        (f (generate-sync-message piped-output-stream origin-path entity)))))
        result (http/post remote-path {:accept-encoding [:application/json]
                                       :content-type :application/json
                                       :body piped-input-stream})]
    (= 200 (:status result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn sync-from-remote
  "Entry point for syncing with another PuppetDB instance. Uses
  `query-fn` to query PuppetDB in process and `submit-command-fn` when
  new reports are found."
  [query-fn submit-command-fn command-payload]
  (pull-records-from-remote query-fn submit-command-fn command-payload))

(defn sync-to-remote
  "Queries the local instance using `query-fn` and constructs a sync
  message to send to `remote-path`. The remote instance will use
  `origin-path` to query for the full report if it's out of date"
  [query-fn origin-path remote-path]
  (let [trigger-sync (partial send-record-hashes-to-remote query-fn origin-path remote-path :v4)]
   (and (trigger-sync :reports ["extract" ["hash" "certname" "start_time"]
                                ["null?" "start_time" false]]
                      {:order_by [[:certname :ascending]]})
        (trigger-sync :factsets ["extract" ["hash" "certname" "producer_timestamp"]
                                 ["null?" "certname" false]]
                      {:order_by [[:certname :ascending]]}))))
