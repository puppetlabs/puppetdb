(ns puppetlabs.puppetdb-sync.command
  (:require [puppetlabs.puppetdb.utils :as utils]
            [clj-http.util :refer [url-encode]]
            [clj-http.client :as http]
            [fs.core :as fs]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-time.coerce :refer [to-timestamp]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull from remote instance

(defn query-report
  "Retrieves a report from `remote-url`"
  [remote-url query]
  (let [{:keys [status body]}
        (http/get (str remote-url "/reports" "?query=" (url-encode (json/generate-string query))))]
    (if (= status 200)
      (json/parse-string body)
      [])))

(defn strip-generated-report-fields
  "hash and receive_time are generated fields and need to be removed
  before submitting the command locally"
  [report]
  (dissoc report "hash" "receive_time"))

(defn query-report-and-transfer!
  "Retrieves report by `hash` from `remote` and submits the command
  locally via `submit-command-fn`"
  [remote submit-command-fn hash]
  (->> (query-report remote ["=" "hash" hash])
       first
       strip-generated-report-fields
       (submit-command-fn :store-report 5)))

(defn compare-streams
  "Compares two lists of reports. Returns the list of reports from
  `ys` that `xs` is missing"
  [xs ys]
  (when (not (empty? ys))
    (let [[[x & xrest :as xs-chunk] xs-rest] (split-with (utils/create-certname-pred xs) xs)
          [[y & yrest :as ys-chunk] ys-rest] (split-with (utils/create-certname-pred ys) ys)
          [to-fetch x1 y1] (cond
                            (empty? xs-chunk)
                            [ys-chunk xs ys-rest]

                            (neg? (compare (:certname x) (:certname y)))
                            [[] xs-rest ys]

                            (pos? (compare (:certname x) (:certname y)))
                            [ys-chunk xs ys-rest]

                            :else
                            [(into [] (remove (set xs-chunk) (set ys-chunk))) xs-rest ys-rest])]
      (concat to-fetch (lazy-seq (compare-streams x1 y1))))))

(defn sync-with-remote
  "Returns a function that is useful in the query streaming query
  callback function in PuppetDB. This function will compare the state
  of the local (in-process) instance with the remote (`sync-data`)
  instance"
  [origin-host-path submit-command-fn sync-data]
  (fn [local-rows]
    (let [sync-data (map #(update-in % [:start_time] to-timestamp) sync-data)]
      (doseq [x (compare-streams local-rows sync-data)]
        (query-report-and-transfer! origin-host-path submit-command-fn (:hash x))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Push to remote instance

(defn generate-sync-message
  "Returns a function suitable for use in the streaming query
  function. Streams the sync data from `rows` to the passed in
  `piped-output-stream. This needs to be run from a separate thread
  than where the associated PipedInputStream thread"
  [piped-out-stream origin-path]
  (fn [rows]
    (with-open [buffered-writer (-> piped-out-stream
                                    java.io.OutputStreamWriter.
                                    java.io.BufferedWriter.)]
      (json/generate-stream {:command "sync"
                             :version 1
                             :payload {:origin_host_path origin-path
                                       :entity_type :reports
                                       :sync_data rows}}
                            buffered-writer))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(pls/defn-validated sync-from-remote
  "Entry point for syncing with another PuppetDB instance. Uses
  `query-fn` to query PuppetDB in process and `submit-command-fn` when
  new reports are found."
  [query-fn submit-command-fn {:keys [origin_host_path entity_type sync_data]}]
  (query-fn :reports :v4 ["extract" ["hash" "certname" "start_time"]
                          ["null?" "start_time" false]]
            {:order_by [[:certname :ascending]]}
            (fn [f]
              (f (sync-with-remote origin_host_path submit-command-fn sync_data)))))

(defn sync-to-remote
  "Queries the local instance using `query-fn` and constructs a sync
  message to send to `remote-path`. The remote instance will use
  `origin-path` to query for the full report if it's out of date"
  [query-fn origin-path remote-path]
  (let [piped-input-stream (java.io.PipedInputStream.)
        piped-output-stream (java.io.PipedOutputStream. piped-input-stream)
        command-fut (future (query-fn :reports
                                      :v4
                                      ["extract" ["hash" "certname" "start_time"]
                                       ["null?" "start_time" false]]
                                      {:order_by [[:certname :ascending]]}
                                      (fn [f]
                                        (f (generate-sync-message piped-output-stream origin-path)))))

        result (http/post remote-path {:accept-encoding [:application/json]
                                       :content-type :application/json
                                       :body piped-input-stream})]
    (= 200 (:status @result))))
