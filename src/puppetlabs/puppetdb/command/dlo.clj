(ns puppetlabs.puppetdb.command.dlo
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [digest]
   [metrics.counters :as counters]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.kitchensink.core :refer [timestamp]]
   [puppetlabs.puppetdb.nio :refer [copts copt-atomic copt-replace oopts]]
   [puppetlabs.puppetdb.queue :as q]
   [puppetlabs.puppetdb.utils :refer [utf8-length utf8-truncate]]
   [puppetlabs.stockpile.queue :as stock])
  (:import
   [java.nio.file AtomicMoveNotSupportedException
    FileAlreadyExistsException Files LinkOption]
   [java.nio.file.attribute FileAttribute]))

(defn- cmd-counters
  "Adds gauges to the dlo for the given category (e.g. \"global\"
  \"replace command\")."
  [registry category]
  (let [dns "puppetlabs.puppetdb.dlo"]
    {:messages (counters/counter registry [dns category "messages"])
     :filesize (counters/counter registry [dns category "filesize"])}))

(defn- update-metrics
  "Updates stats to reflect the receipt of the named command."
  [metrics command size]
  (let [global (metrics "global")
        cmd (metrics command)]
    (counters/inc! (:messages global))
    (counters/inc! (:filesize global) size)
    (counters/inc! (:messages cmd))
    (counters/inc! (:filesize cmd) size))
  metrics)

(defn- ensure-cmd-metrics [metrics registry cmd]
  (if (metrics cmd)
    metrics
    (assoc metrics cmd (cmd-counters registry cmd))))

(def ^:private parse-metadata
  (q/metadata-parser (assoc q/metadata-command->puppetdb-command
                            "unknown" "unknown")))

(def ^:private serialize-metadata
  (q/metadata-serializer (assoc q/puppetdb-command->metadata-command
                                "unknown" "unknown")))

(defn- parse-cmd-filename
  [filename]
  (let [id-meta-split-rx #"([0-9]+)-(.*)"]
    (when-let [[_ id qmeta] (re-matches id-meta-split-rx filename)]
      (parse-metadata qmeta))))

(defn- metrics-for-dir
  "Updates metrics to reflect the discarded commands directly inside
  the path; does not recurse."
  [registry path]
  (with-open [path-stream (Files/newDirectoryStream path)]
    (reduce (fn [metrics p]
              ;; Assume the trailing .json here and in
              ;; entry-cmd-data-filename below.
              (let [name (-> p .getFileName str)]
                (if-let [cmd (:command (parse-cmd-filename name))]
                  (update-metrics (ensure-cmd-metrics metrics registry cmd)
                                  cmd
                                  (Files/size p))
                  metrics)))
            {"global" (cmd-counters registry "global")}
            path-stream)))

(defn- write-failure-metadata
  "Given a (possibly empty) sequence of command attempts and an exception,
  writes a summary of the failure to out."
  [out attempts]
  (let [n (count attempts)]
    (dorun (map-indexed (fn [i {:keys [exception time]}]
                          (.format out "%sAttempt %d @ %s\n"
                                   (into-array Object [(if (zero? i) "" "\n")
                                                       (- n i)
                                                       time]))
                          (.printStackTrace exception out))
                        attempts))))

(defn- strip-metadata-suffix
  [queue-metadata-str]
  (str/replace queue-metadata-str #"\.json$" ""))

(defn- dlo-filename
  [id metadata suffix]
  (str id \-
       (utf8-truncate metadata (- 255
                                  (count (str id))
                                  1 ; dash after id
                                  (utf8-length suffix)))
       suffix))

(defn- entry-cmd-data-filename
  "Returns a string representing the filename for the given stockpile `entry`.
  The filename is similar to what stockpile would store the message as
  '10291-1469469689_cat_4_foo.org.json', but in contrast 'unknown' is an allowed
  command name."
  [entry]
  (let [id (stock/entry-id entry)
        metadata (stock/entry-meta entry)]
    (dlo-filename id metadata "")))

(defn- entry-cmd-err-filename
  "Creates a filename string for error metadata associated with the
  stockpile message at `id`. This filename is similar to how stockpile
  would store it but includes a error stuffix to differentiate it,
  similar to the following '10291-1469469689_cat_4_foo.org_err.txt'"
  [id metadata]
  (dlo-filename id (strip-metadata-suffix metadata) "_err.txt"))

(defn- store-failed-command-info
  [id metadata command attempts dir]
  ;; We want the metdata and command so we don't have to reparse, and
  ;; we want the command because id isn't unique by itself (given
  ;; unknown commands).
  (let [tmp (Files/createTempFile dir
                                  ;; no need to truncate, implementation will
                                  (str "tmperr-" id \- command) ""
                                  (make-array FileAttribute 0))]
    ;; Leave the temp file if something goes wrong.
    (with-open [out (java.io.PrintWriter. (.toFile tmp))]
      (write-failure-metadata out attempts))
    (let [dest (.resolve dir (entry-cmd-err-filename id metadata))
          moved? (try
                   (Files/move tmp dest (copts [copt-atomic]))
                   (catch FileAlreadyExistsException ex
                     true)
                   (catch UnsupportedOperationException ex
                     false)
                   (catch AtomicMoveNotSupportedException ex
                     false))]
      (when-not moved?
        (Files/move tmp dest (copts [copt-replace])))
      dest)))

;;; Public interface

(defn initialize
  "Initializes the dead letter office (DLO), at the given path (a Path),
   creating the directory if it doesn't exist, adds related metrics to
   the registry, and then returns a representation of the DLO."
  [path registry]
  (try
    (Files/createDirectory path (make-array FileAttribute 0))
    (catch FileAlreadyExistsException ex
      (when-not (Files/isDirectory path (make-array LinkOption 0))
        (throw (Exception. (trs "DLO path {0} is not a directory" path))))))
  {:path path
   :registry registry
   :metrics (atom (metrics-for-dir registry path))})

(defn discard-cmdref
  "Stores information about the failed `stockpile` `cmdref` to the
  `dlo` (a Path) for later inspection.  Saves two files named like
  this:
    10291-1469469689_cat_4_foo.org.json
    10291-1469469689_cat_4_foo.org_err.txt
  The first contains the failed command itself, and the second details
  the cause of the failure.  The command will be moved from the
  `stockpile` queue to the `dlo` directory (a Path) via
  stockpile/discard.  Returns {:info Path :command Path}."
  [cmdref stockpile dlo]
  (let [{:keys [path registry metrics]} dlo
        {:keys [id received command attempts]} cmdref
        entry (stock/entry id (serialize-metadata received cmdref true))
        cmd-dest (.resolve path (entry-cmd-data-filename entry))]
    ;; We're going to assume that our moves will be atomic, and if
    ;; they're not, that we don't care about the possibility of
    ;; partial dlo messages.  If needed, the existence of the err file
    ;; can be used as an indicator that the dlo message is complete.
    (stock/discard stockpile entry cmd-dest)
    (let [id (stock/entry-id entry)
          metadata (stock/entry-meta entry)
          info-dest (store-failed-command-info id metadata command
                                               attempts
                                               path)]
      (swap! metrics ensure-cmd-metrics registry command)
      (update-metrics @metrics command (Files/size cmd-dest))
      {:info info-dest
       :command cmd-dest})))

(defn discard-bytes
  "Stores information about a failed command to the `dlo` (a Path) for
  later inspection.  `attempts` must be a list of {:time t :exception
  ex} maps in reverse chronological order.  Saves two files named like
  this:
    10291-1469469689_unknown_0_BYTESHASH
    10291-1469469689_unknown_0_BYTESHASH.txt
  The first contains the bytes provided, and the second details the
  cause of the failure.  Returns {:info Path :command Path}."
  [bytes id received attempts dlo]
  ;; For now, we assume that we don't need durability, and that we
  ;; don't care about the possibility of partial dlo messages.  If
  ;; needed, the existence of the err file can be used as an
  ;; indicator that the unknown message may be complete.
  (let [{:keys [path registry metrics]} dlo
        digest (digest/sha1 [bytes])
        metadata (serialize-metadata received
                                     {:producer-ts nil
                                      :command "unknown"
                                      :version 0
                                      :certname digest
                                      :compression ""}
                                     true)
        cmd-dest (.resolve path (str id \- metadata))]
    (Files/write cmd-dest bytes (oopts []))
    (let [info-dest (store-failed-command-info id metadata "unknown"
                                               attempts
                                               path)]
      (swap! metrics ensure-cmd-metrics registry "unknown")
      (update-metrics @metrics "unknown" (Files/size cmd-dest))
      {:info info-dest :command cmd-dest})))
