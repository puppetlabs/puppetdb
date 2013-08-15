(ns com.puppetlabs.puppetdb.command.dlo
  (:import [org.apache.commons.io FileUtils])
  (:require [clojure.string :as string]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.utils :as pl-utils]
            [clj-time.format :as time-format]
            [cheshire.core :as json]
            [fs.core :as fs]
            [clojure.java.io :refer [file make-parents]]
            [clj-time.core :refer [ago after?]]
            [metrics.gauges :refer [gauge]]
            [metrics.meters :refer [meter mark!]]
            [metrics.timers :refer [timer time!]]
            [com.puppetlabs.time :refer [period?]]))

(def ns-str (str *ns*))

(def metrics (atom {}))

(defn- subdirectories
  "Returns the list of subdirectories of the DLO `dir`."
  [dir]
  {:pre [(or (string? dir)
             (instance? java.io.File dir))]
   :post [(every? (partial instance? java.io.File) %)]}
  (filter fs/directory? (.listFiles (file dir))))

(defn messages
  "Returns the list of messages in the given DLO `subdir`. This is every file
  except the .tgz or .partial files."
  [subdir]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]
   :post [(every? (partial instance? java.io.File) %)]}
  (filter #(and (fs/file? %)
                (not (#{".tgz" ".partial"} (fs/extension %))))
          (.listFiles subdir)))

(defn archives
  "Returns the list of message archives in the given DLO `subdir`."
  [subdir]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]
   :post [(every? (partial instance? java.io.File) %)]}
  (fs/glob (file subdir "*.tgz")))

(defn last-archived
  "Returns the timestamp of the latest archive file in DLO `subdir`, indicating
  the most recent time the directory was archived."
  [subdir]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]
   :post [(or (nil? %)
              (pl-utils/datetime? %))]}
  (->> (archives subdir)
       (map #(fs/base-name % ".tgz"))
       (map #(time-format/parse (time-format/formatters :date-time) %))
       (sort)
       (last)))

(defn- create-metrics-for-dlo!
  "Creates the standard set of global metrics."
  [dir]
  (when-not (:global @metrics)
    (swap! metrics assoc-in [:global :compression] (timer [ns-str "global" "compression"]))
    (swap! metrics assoc-in [:global :compression-failures] (meter [ns-str "global" "compression-failures"] "failures/s"))
    (swap! metrics assoc-in [:global :filesize] (gauge [ns-str "global" "filesize"] (FileUtils/sizeOf dir)))
    (swap! metrics assoc-in [:global :messages] (gauge [ns-str "global" "messages"] (count (mapcat messages (subdirectories dir)))))
    (swap! metrics assoc-in [:global :archives] (gauge [ns-str "global" "archives"] (count (mapcat archives (subdirectories dir)))))))

(defn- create-metrics-for-subdir!
  "Creates the standard set of metrics for the given `subdir`, using its
  basename as the identifier for its metrics."
  [subdir]
  (let [subdir-name (fs/base-name subdir)
        prefix [ns-str subdir-name]]
    (when-not (get @metrics subdir-name)
      (swap! metrics assoc-in [subdir-name :compression] (timer (conj prefix "compression")))
      (swap! metrics assoc-in [subdir-name :compression-failures] (meter (conj prefix "compression-failures") "failures/s"))
      (swap! metrics assoc-in [subdir-name :filesize] (gauge (conj prefix "filesize") (FileUtils/sizeOf subdir)))
      (swap! metrics assoc-in [subdir-name :messages] (gauge (conj prefix "messages") (count (messages subdir))))
      (swap! metrics assoc-in [subdir-name :archives] (gauge (conj prefix "archives") (count (archives subdir))))
      (swap! metrics assoc-in [subdir-name :last-archived] (gauge (conj prefix "archives") (last-archived subdir))))))

(defn- global-metric
  "Returns the global metric corresponding to `metric`."
  [metric]
  (get-in @metrics [:global metric]))

(defn- subdir-metric
  "Returns the subdir-specific metric for `subdir` corresponding to `metric`.
  The metric path uses the basename of the subdir, so we compute that."
  [subdir metric]
  (let [subdir-name (fs/base-name subdir)]
    (get-in @metrics [subdir-name metric])))

(defn summarize-attempt
  "Convert an 'attempt' annotation for a message into a string summary,
  including timestamp, error information, and stacktrace."
  [index {:keys [timestamp error trace] :as attempt}]
  (let [trace-str (string/join "\n" trace)
        index     (if (nil? index) index (inc index))]
    (format "Attempt %d @ %s\n\n%s\n%s\n" index timestamp error trace-str)))

(defn summarize-exception
  "Convert a Throwable into a string summary similar to the output of
  summarize-attempt."
  [e]
  (let [attempt {:timestamp (pl-utils/timestamp)
                 :error     (str e)
                 :trace     (.getStackTrace e)}]
    (summarize-attempt nil attempt)))

(defn produce-failure-metadata
  "Given a (possibly empty) sequence of message attempts and an exception,
  return a header string of the errors."
  [attempts exception]
  (let [attempt-summaries (map-indexed summarize-attempt attempts)
        exception-summary (if exception (summarize-exception exception))]
    (string/join "\n" (concat attempt-summaries [exception-summary]))))

(defn store-failed-message
  "Stores a failed message for later inspection. This will be stored under
  `dir`, in a path shaped like `dir`/<command>/<timestamp>-<checksum>. If the
  message was not parseable, `command` will be parse-error."
  [msg e dir]
  (let [command  (string/replace (get msg :command "parse-error") " " "-")
        attempts (get-in msg [:annotations :attempts])
        metadata (produce-failure-metadata attempts e)
        msg      (if (string? msg) msg (json/generate-string msg))
        contents (string/join "\n\n" [msg metadata])
        checksum (pl-utils/utf8-string->sha1 contents)
        subdir   (file dir command)
        basename (format "%s-%s" (pl-utils/timestamp) checksum)
        filename (file subdir basename)]
    (create-metrics-for-dlo! dir)
    (create-metrics-for-subdir! subdir)
    (make-parents filename)
    (spit filename contents)))

(defn compressible-files
  "Lists the compressible files in the DLO subdirectory `subdir`. These are
  non-tgz files older than `threshold`."
  [subdir threshold]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]
   :post [(every? (partial instance? java.io.File) %)]}
  (let [cutoff-time (.getMillis (ago threshold))]
    (filter #(< (fs/mod-time %) cutoff-time) (messages subdir))))

(defn already-archived?
  "Returns true if this `subdir` of the DLO has already been archived within the
  last `threshold` amount of time, and false if not. This checks if a .tgz file
  exists with a timestamp newer than `threshold`, and ensures that we will only
  archive once per `threshold`."
  [subdir threshold]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]}
  (if-let [archive-time (last-archived subdir)]
    (after? archive-time (ago threshold))))

(defn- compress-subdir!
  "Compresses the specified `files` in the particular command-specific
  subdirectory `subdir`, replacing them with a timestamped .tgz file."
  [subdir files]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]}
  (create-metrics-for-subdir! subdir)
  (let [target-file (file subdir (str (pl-utils/timestamp) ".tgz"))
        temp (str target-file ".partial")]
    (try
      (time! (subdir-metric subdir :compression)
             (apply archive/tar temp "UTF-8" (map (juxt fs/base-name slurp) files))
             (fs/rename temp target-file)
             (doseq [filename files]
               (fs/delete filename)))
      (catch Throwable e
        (mark! (global-metric :compression-failures))
        (mark! (subdir-metric subdir :compression-failures))
        (throw e))
      (finally
        (fs/delete temp)))))

(defn compress!
  "Compresses all DLO subdirectories which have messages that have been in the
  directory for longer than `threshold`. This will produce a timestamped .tgz
  file in each subdirectory containing old messages."
  [dir threshold]
  {:pre [(or (string? dir)
             (instance? java.io.File dir))
         (period? threshold)]}
  (create-metrics-for-dlo! dir)
  (when-let [subdir-files (seq (for [subdir (remove #(already-archived? % threshold) (subdirectories dir))
                                :let [files (compressible-files subdir threshold)]
                                :when (seq files)]
                            [subdir files]))]
    (time! (global-metric :compression)
           (doseq [[subdir files] subdir-files]
             (compress-subdir! subdir files)))))
