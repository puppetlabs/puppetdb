(ns puppetlabs.puppetdb.sync.command
  (:require [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client]
            [puppetlabs.puppetdb.cheshire :as json]
            [cheshire.core :as cheshire]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; How to sync each entity

(def report-key (juxt :certname :hash))

(declare maybe-update-in)

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
                          (maybe-update-in report [:resource_events] #(map clean-up-resource-event %)))

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
                              (maybe-update-in [:edges] #(map clean-up-edge %))
                              (maybe-update-in [:resources] #(map clean-up-resource %))))
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

(defn maybe-update-in
  "Like update-in, except it won't create `path` if it doesn't exist."
  [x path f]
  (if (get-in x path)
    (update-in x path f)
    x))

(defn http-get
  "A wrapper around clj-http.client/get which takes a custom error formatter,
  `(fn [status body] ...)`, to provide a message which is written to the log on
  failure status codes. '"
  [url opts error-message-fn]
  (try+
   (log/debug (format "HTTP GET %s %s" url opts))
   (clj-http.client/get url opts)
   (catch :status  {:keys [body status] :as response}
     (throw+ {:type ::remote-host-error
              :error-response response
              :throw-exceptions true
              :throw-entire-message true}
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

(defn query-remote
  "Queries a resource from `remote-url`/`entity`, where
  `entity` is a keyword like :reports, :factsets, etc. '"
  [remote-url entity query]
  (let [json-query (json/generate-string query)
        url (str remote-url "/" (name entity))
        error-message-fn (fn [status body] (format "Error querying %s for %s with query %s. Received HTTP status code %s with the error message '%s'"
                                                   remote-url (name entity) query status body))
        {:keys [status body]} (http-get url {:query-params {:query json-query}} error-message-fn)]
    (json/parse-string body true)))

(defn query-record-and-transfer!
  "Query for a record where `record-fetch-key` (from `sync-config`) equals
  `record-fetch-val` and submit a command locally to copy that record, via
  `store-record-locally-fn`."
  [remote-url record-fetch-val submit-command-fn sync-config]
  (let [{:keys [entity record-fetch-key clean-up-record-fn submit-command]} sync-config
        store-record-locally-fn (partial submit-command-fn (:command submit-command) (:version submit-command))]
    (-> (query-remote remote-url entity ["and" ["=" (name record-fetch-key) record-fetch-val]
                                               include-inactive-nodes-criteria])
        first
        (collapse-and-download-collections remote-url)
        strip-timestamp-and-hash
        clean-up-record-fn
        store-record-locally-fn)))

(defn set-local-deactivation-status!
  [{:keys [certname deactivated] :as remote-record} submit-command-fn]
  ;; deactivated never goes false (null) by itself; one of the other entities
  ;; will change, reactivating it as a side effect
  (when deactivated
    (submit-command-fn :deactivate-node 3
                       {:certname certname
                        :producer_timestamp deactivated})))

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

(defn streamed-summary-query
  "Perform the summary query at `remote-url`, as specified in
  `sync-config`. Returns a stream which must be closed."
  [remote-url sync-config]
  (let [{:keys [entity record-hashes-query ]} sync-config
        {:keys [version query order]} record-hashes-query
        url (str remote-url "/" (name entity))
        error-message-fn (fn [status body]
                           (format "Error querying %s for record summaries (%s). Received HTTP status code %s with the error message '%s'"
                                   remote-url entity status body))]
        (-> url
            (http-get {:query-params {:query (json/generate-string query)
                                      :order_by (order-by-clause-to-wire-format (json/generate-string order))}
                       :as :stream
                       :throw-entire-message true}
                      error-message-fn)
            :body)))

(defn pull-records-from-remote!
  "Query `remote-url` using the query api and the local PDB using `query-fn`,
  using the entity and comparison information in `sync-config` (see comments
  above). If the remote record is newer than the local one or it doesn't exist locally,
  download it over http and place it in the queue with `submit-command-fn`."
  [query-fn submit-command-fn remote-url sync-config]
  (let [summary-response-stream (streamed-summary-query remote-url sync-config)]
    (try
      (let [remote-sync-data (map parse-time-fields
                                  (-> summary-response-stream clojure.java.io/reader (json/parse-stream true)))
            {:keys [entity record-hashes-query record-id-fn record-ordering-fn record-fetch-key]} sync-config
            {:keys [version query order]} record-hashes-query]
        (query-fn entity version query order
                  (query-result-handler (fn [local-sync-data]
                                          (let [records-to-fetch (records-to-fetch record-id-fn
                                                                                   record-ordering-fn
                                                                                   local-sync-data
                                                                                   remote-sync-data)]
                                            (doseq [record records-to-fetch]
                                              (if (= entity :nodes)
                                                (set-local-deactivation-status! record submit-command-fn)
                                                (query-record-and-transfer! remote-url
                                                                            (get record record-fetch-key)
                                                                            submit-command-fn
                                                                            sync-config))))))))
      (finally
        (.close summary-response-stream)))))

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
  [query-fn submit-command-fn remote-url]
  (let [submit-command-fn (wrap-with-logging submit-command-fn :debug "Submitting command")]
   (doseq [sync-config sync-configs]
     (try+
      (pull-records-from-remote! query-fn submit-command-fn remote-url sync-config)

      (catch [:type ::remote-host-error] _
        (let [{:keys [throwable message]} &throw-context]
          (log/errorf throwable "Sync from remote host failed due to: %s" message)))))))
