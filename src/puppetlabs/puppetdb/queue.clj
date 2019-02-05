(ns puppetlabs.puppetdb.queue
  (:import [java.nio.charset StandardCharsets]
           [java.io InputStreamReader BufferedReader InputStream]
           [java.util TreeMap HashMap]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute]
           [org.apache.commons.compress.compressors.gzip GzipCompressorInputStream])
  (:require [clojure.string :as str :refer [re-quote-replacement]]
            [puppetlabs.stockpile.queue :as stock]
            [clj-time.coerce :as tcoerce]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants :as command-constants]
            [puppetlabs.puppetdb.constants :as constants]
            [metrics.timers :refer [timer time!]]
            [metrics.counters :refer [inc!]]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as kitchensink]
            [slingshot.slingshot :refer [throw+]]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protos]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.utils :refer [compression-file-extension-schema
                                               content-encodings->file-extensions
                                               match-any-of utf8-length
                                               utf8-truncate]]
            [slingshot.slingshot :refer [try+]]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.time :as pdbtime]))

(def metadata-command->puppetdb-command
  ;; note that if there are multiple metadata names for the same command then
  ;; puppetdb-command->metadata-command (defined below) will have to be modified
  ;; in order to establish a "preferred" metadata name for each command
  {"catalog" "replace catalog"
   "facts" "replace facts"
   "rm-node" "deactivate node"
   "report" "store report"})

(def puppetdb-command->metadata-command
  (into {} (for [[md pdb] metadata-command->puppetdb-command] [pdb md])))

(defn stream->json [^InputStream stream]
  (try
    (-> stream
        (InputStreamReader. StandardCharsets/UTF_8)
        BufferedReader.
        (json/parse-stream true))
    (catch Exception e
      (throw+ {:kind ::parse-error} e "Error parsing command"))))

(defn sanitize-certname
  "Replace any underscores and filename forbidden characters found in `certname`
  with dashes."
  [certname]
  (let [forbidden-chars (conj constants/filename-forbidden-characters
                              "_")]
    (str/replace
      certname
      (re-pattern (str "("
                       (->> forbidden-chars
                           (map #(format "\\Q%s\\E" %)) ; escape regex chars
                           (str/join "|"))
                       ")"))
      "-")))

(defn max-certname-length
  "Given the received-at time, metadata command name and command version for
  a metadata string, returns the maximum allowable length (in bytes) of
  a certname in that string. Note that this length is only achievable if the
  certname does not need to be sanitized, since sanitized certnames always have
  a SHA1 hash appended."
  [received metadata-command version]
  (let [time-length (-> received tcoerce/to-long str utf8-length)
        command-length (utf8-length metadata-command)
        version-length (utf8-length (str version))
        field-separators 3]
    (- 255 ; overall filename length limit
       time-length command-length version-length
       3 ; number of field separators (underscores)
       5))) ; length of '.json' suffix in UTF-8

(defn truncated-certname-length
  "Given the received-at time, metadata command name, and command version that
  will be in a metadata string, returns the length (in bytes) to truncate
  certnames to when they are longer than the `max-certname-length` for the
  string or they contain characters that must be sanitized. This is less than
  the string's `max-certname-length` to leave space for the SHA1 hash."
  [received metadata-command version]
  (- (max-certname-length received metadata-command version)
     1 ; for the additional field separator between certname and hash
     constants/sha1-hex-length))

(defn embeddable-certname
  "Takes all the components of a metadata string, and returns a version of the
  certname that's safe to embed in the metadata string. It will be sanitized to
  replace any illegal filesystem characters and underscores with dashes, and
  will be truncated if the original version will cause the metadata string to
  exceed 255 characters."
  [received metadata-command version certname]
  (let [cn-length (utf8-length certname)
        trunc-length (truncated-certname-length received metadata-command version)
        sanitized-certname (sanitize-certname certname)]
    (if (or (> cn-length (max-certname-length received metadata-command version))
            (and (not= certname sanitized-certname)
                 (> cn-length trunc-length)))
      (utf8-truncate sanitized-certname trunc-length)
      sanitized-certname)))

(defn encode-command-time
  "This takes the two time fields in the command header and encodes it
  in a way that is more compact. These times need to be included in
  the stockpile metadata string, so they should be as short as
  possible. This will return a string that is `received-ts` as a long,
  followed by a + or - and the difference between `received-ts` and
  `producer-ts` as a long."
  [received-ts producer-ts]
  (let [received-time (tcoerce/to-long received-ts)]
    (if-let [producer-offset (and producer-ts
                                  (- (tcoerce/to-long producer-ts)
                                     received-time))]
      (str received-time
           (when-not (neg? producer-offset) \+)
           producer-offset)
      (str received-time))))

(defn metadata-serializer
  "Given an (optional) map between the command names used in the rest of
  PuppetDB and the command names to use in metadata strings, return a function
  that serializes command metadata to a string. If no map is provided, then the
  map `puppetdb-command->metadata-command` defined in this namespace is used.
  Note that the certname in the string will not be the same as the original
  certname if the certname is long or contains filesystem special characters."
  ([] (metadata-serializer puppetdb-command->metadata-command))
  ([puppetdb-command->metadata-command]
   (fn [received {:keys [producer-ts command version certname compression]}]
     (when-not (puppetdb-command->metadata-command command)
       (throw (IllegalArgumentException. (trs "unknown command ''{0}''" command))))
     (let [certname (or certname "unknown-host")
           received+producer-time (encode-command-time received producer-ts)
           short-command (puppetdb-command->metadata-command command)
           safe-certname (embeddable-certname received short-command version certname)
           name (if (= safe-certname certname)
                  safe-certname
                  (format "%s_%s"
                          safe-certname
                          (kitchensink/utf8-string->sha1 certname)))
           extension (str "json" (if (not-empty compression)
                                   (str "." compression)))]
       (format "%s_%s_%d_%s.%s"
               received+producer-time short-command version name extension)))))

(def serialize-metadata (metadata-serializer))

(defn validate-compression-extension-syntax [ext]
  (assert (re-matches #"[a-zA-Z0-9]+" ext))
  ext)

(def compression-extension-rx-group
  (format "\\.(%s)"
          (->> content-encodings->file-extensions
               vals
               (remove #{""})
               (map validate-compression-extension-syntax)
               (str/join "|"))))

(defn metadata-rx [valid-commands]
  (re-pattern (str
               "([0-9]+)([+|-][0-9]+)?_"
               (match-any-of valid-commands)
               "_([0-9]+)_(.*)\\.json(?:" compression-extension-rx-group ")?")))

(defn metadata-parser
  "Given an (optional) map between the command names that appear in metadata
  strings and the command names used in the rest of PuppetDB, return a function
  that parses a queue metadata string. If no map is provided, then the map
  `metadata-command->puppetdb-command` defined in this namespace is used.
  Note that the certname in this result will not be the same as the original
  certname if the certname is long or contains filesystem special characters."
  ([] (metadata-parser metadata-command->puppetdb-command))
  ([metadata-command->puppetdb-command]
   ;; NOTE: changes here may affect the DLO, e.g. it currently assumes
   ;; the trailing .json.
   (let [rx (metadata-rx (keys metadata-command->puppetdb-command))
         md-cmd->pdb-cmd metadata-command->puppetdb-command]
     (fn [s]
       (when-let [[_ received-stamp producer-offset md-command version certname compression] (re-matches rx s)]
         (let [received-time-long (Long/parseLong received-stamp)
               producer-offset (and producer-offset (Long/parseLong producer-offset))]
           (and certname
                {:received (-> received-time-long
                               tcoerce/from-long
                               kitchensink/timestamp)
                 :producer-ts (some-> producer-offset
                                      (+ received-time-long)
                                      tcoerce/from-long)
                 :version (Long/parseLong version)
                 :command (get md-cmd->pdb-cmd md-command "unknown")
                 :certname certname
                 :compression (if (seq compression) compression "")})))))))

(def parse-metadata (metadata-parser))

(def command-req-schema
  "Represents an incoming command, before it has been enqueued"
  {:command (apply s/enum (vals metadata-command->puppetdb-command))
   :version s/Int
   :certname s/Str
   :producer-ts (s/maybe pls/Timestamp)
   :callback (s/=> s/Any s/Any)
   :command-stream java.io.InputStream
   :compression compression-file-extension-schema})

(s/defn create-command-req :- command-req-schema
  "Validating constructor function for command requests"
  [command :- s/Str
   version :- s/Int
   certname :- s/Str
   producer-ts :- (s/maybe s/Str)
   compression :- compression-file-extension-schema
   callback :- (s/=> s/Any s/Any)
   command-stream :- java.io.InputStream]
  {:command command
   :version version
   :certname certname
   :producer-ts (when producer-ts
                  (pdbtime/from-string producer-ts))
   :compression compression
   :callback callback
   :command-stream command-stream})

(defrecord CommandRef [id command version certname received producer-ts callback delete? compression])

(defn cmdref->entry [{:keys [id received] :as cmdref}]
  (stock/entry id (serialize-metadata received cmdref)))

(defn entry->cmdref [entry]
  (let [{:keys [received command version
                certname producer-ts compression]} (-> entry
                                                       stock/entry-meta
                                                       parse-metadata)]
    (map->CommandRef {:id (stock/entry-id entry)
                      :command command
                      :version version
                      :certname certname
                      :received received
                      :producer-ts producer-ts
                      :callback identity
                      :compression compression})))

(defn wrap-decompression-stream
  [file-extension command-stream]
  (case file-extension
    nil command-stream
    "" command-stream
    "gz" (GzipCompressorInputStream. command-stream)
    (throw+ {:kind ::parse-error} nil
            (trs "Unsupported compression file extension for command: {0}"
                 file-extension))))

(defn cmdref->cmd [q cmdref]
  "Returns the command associated with cmdref, or nil if the file is
  missing (i.e. it's been deleted)."
  (let [compression (:compression cmdref)
        entry (cmdref->entry cmdref)
        stream (try+
                (stock/stream q entry)
                (catch [:kind ::stock/no-such-entry] {:keys [entry source]}
                  ;; Do we want the entry, path or both in the log?
                  ;; The entry will contain the id and metadata
                  ;; string; source is the actual path.
                  (log/error (trs "Command has disappeared: {0}"
                                  (pr-str entry)))
                  nil))]
    (when stream
      (with-open [command-stream stream]
        (assoc cmdref
               ;; Don't explicitly buffer the compressed data stream.
               ;; Testing has revealed that buffering the stream
               ;; feeding the decompressor can reduce json throughput
               ;; by up to 400x.  Note that stream->json will already
               ;; be applying a BufferedReader to the incoming bytes.
               :payload (stream->json (wrap-decompression-stream
                                       compression
                                       command-stream))
               :entry entry)))))

(defn cons-attempt [cmdref exception]
  (update cmdref :attempts conj {:exception exception
                                 :time (kitchensink/timestamp)}))

(s/defn store-in-stockpile
  [q
   received :- pls/Timestamp
   command-req :- command-req-schema]
  (try+
   (stock/store q
                (:command-stream command-req)
                (serialize-metadata received command-req))

   (catch [:kind ::stock/unable-to-commit] {:keys [stream-data]}
     ;; stockpile has saved all of the data in command-stream to
     ;; stream-data, but was unable to finish (e.g. the rename
     ;; failed).  Try to delete the (possibly large) temp file and
     ;; then rethrow the exception for logging at the "top level".
     (try
       (Files/delete stream-data)
       (log/warn (trs "Cleaned up orphaned command temp file: {0}"
                      (pr-str (str stream-data))))
       (catch Exception ex
         (log/warn (trs "Unable to clean up orphaned command temp file: {0}"
                        (pr-str (str stream-data))))))
     (throw+))

   (catch [:kind ::path-cleanup-failure-after-error] {:keys [path exception]}
     ;; stockpile/store failed with the exception described by
     ;; the :cause, and then, on the way out, the attempt to remove the temporary
     ;; file described by path failed with the given exception.
     ;; Don't try to delete the path again, just log the path with the
     ;; removal exception, and then rethrow the exception for logging
     ;; at the "top level".
     (log/warn (trs "Unable to remove temp file while trying to store incoming command: {0}"
                    (pr-str (str path))))
     (throw+))))

(s/defn store-command
  [q
   command-req :- command-req-schema]
  (let [current-time (time/now)
        entry (store-in-stockpile q
                                  current-time
                                  command-req)]
    (-> command-req
        (select-keys [:command :version :certname :producer-ts :callback :compression])
        (assoc :id (stock/entry-id entry)
               :received (kitchensink/timestamp current-time))
        map->CommandRef)))

(defn ack-command
  [q command]
  (stock/discard q (:entry command)))

(deftype SortedCommandBuffer [^TreeMap fifo-queue
                              ^HashMap certnames-map
                              ^long max-entries
                              ^clojure.lang.IFn delete-update-fn]
  async-protos/Buffer
  (full? [this]
    (>= (.size fifo-queue) max-entries))

  (remove! [this]
    (let [^CommandRef cmdref (val (.pollFirstEntry fifo-queue))
          command-type (:command cmdref)]
      (when (or (= command-type "replace catalog")
                (= command-type "replace facts"))
        (.remove certnames-map [command-type (:certname cmdref)]))
      cmdref))

  (add!* [this item]
    (when-not (instance? CommandRef item)
      (throw (IllegalArgumentException. (trs "Cannot enqueue item of type {0}" (class item)))))

    (let [^CommandRef cmdref item
          {command-type :command
           certname :certname
           producer-ts :producer-ts} cmdref]

      (if (and producer-ts
               (or (= command-type "replace catalog")
                   (= command-type "replace facts")))
        (let [^CommandRef maybe-old-command (.get certnames-map [command-type certname])]
          (cond
            (nil? maybe-old-command)
            (do
              (.put certnames-map [command-type certname] cmdref)
              (.put fifo-queue (:id cmdref) cmdref))

            (time/after? producer-ts
                         (:producer-ts maybe-old-command))
            (do
              (.put certnames-map [command-type certname] cmdref)
              (.put fifo-queue
                    (:id maybe-old-command)
                    (assoc maybe-old-command :delete? true))
              (.put fifo-queue (:id cmdref) cmdref)
              (delete-update-fn (:command maybe-old-command) (:version maybe-old-command)))

            :else
            (do
              (.put fifo-queue
                    (:id cmdref)
                    (assoc cmdref :delete? true))
              (delete-update-fn (:command cmdref) (:version cmdref)))))

        (.put fifo-queue (:id cmdref) cmdref)))
    this)

  (close-buf! [this])
  clojure.lang.Counted
  (count [this]
    (.size fifo-queue)))

(defn sorted-command-buffer
  ([^long n]
   (sorted-command-buffer n (constantly nil)))
  ([^long n ^clojure.lang.IFn delete-update-fn]
   ;; accepting a function here is a hack to get around a cyclic dependency
   ;; between this ns, mq-listener.clj, and dlo.clj. My hope is we'll be able
   ;; to get rid of it somehow when we refactor mq-listener and command.clj.
   (SortedCommandBuffer. (TreeMap.) (HashMap.) n delete-update-fn)))

(defn message-loader
  "Returns a function that will enqueue existing stockpile messages to
  `command-chan`. Messages with ids less than `message-id-ceiling`
  will be loaded to guard against duplicate processing of commands
  when new commands are enqueued before all existing commands have
  been enqueued. Note that there is no guarantee on the enqueuing
  order of commands read from stockpile's reduce function"
  [q message-id-ceiling]
  (fn [command-chan update-metrics]
    (stock/reduce q
                  (fn [chan entry]
                    ;;This conditional guards against a new command
                    ;;enqueued in the same directory before we've
                    ;;read all existing files
                    (when (< (stock/entry-id entry) message-id-ceiling)
                      (let [{:keys [command version] :as cmdref} (entry->cmdref entry)]
                        (async/>!! chan cmdref)
                        (update-metrics command version)))
                    chan)
                  command-chan)))

(defn create-or-open-stockpile
  "Opens an existing stockpile queue if one is present otherwise
  creates a new stockpile queue at `queue-dir`"
  [queue-dir]
  (let [stockpile-root (kitchensink/absolute-path queue-dir)
        queue-path (get-path stockpile-root "cmd")]
    (if-let [q (and (Files/exists queue-path (make-array LinkOption 0))
                    (stock/open queue-path))]
      [q (message-loader q (stock/next-likely-id q))]
      (do
        (Files/createDirectories (get-path stockpile-root)
                                 (make-array FileAttribute 0))
        [(stock/create queue-path) nil]))))
