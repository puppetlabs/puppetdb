(ns com.puppetlabs.puppetdb.command.dlo
  (:require [clojure.string :as string]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.utils :as pl-utils]
            [clj-time.format :as time-format]
            [cheshire.core :as json]
            [fs.core :as fs])
  (:use [clojure.java.io :only [file make-parents]]
        [clj-time.core :only [ago after?]]
        [com.puppetlabs.time :only [period?]]))

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
  (let [command  (get msg :command "parse-error")
        attempts (get-in msg [:annotations :attempts])
        metadata (produce-failure-metadata attempts e)
        msg      (if (string? msg) msg (json/generate-string msg))
        contents (string/join "\n\n" [msg metadata])
        checksum (pl-utils/utf8-string->sha1 contents)
        subdir   (string/replace command " " "-")
        basename (format "%s-%s" (pl-utils/timestamp) checksum)
        filename (file dir subdir basename)]
    (make-parents filename)
    (spit filename contents)))

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

(defn compressible-files
  "Lists the compressible files in the DLO subdirectory `subdir`. These are
  non-tgz files older than `threshold`."
  [subdir threshold]
  {:pre [(or (string? subdir)
             (instance? java.io.File subdir))]
   :post [(every? (partial instance? java.io.File) %)]}
  (let [cutoff-time (.getMillis (ago threshold))
        file-filter #(and
                       (fs/file? %)
                       (not= (fs/extension %) ".tgz")
                       (< (fs/mod-time %) cutoff-time))]
    (filter file-filter (messages subdir))))

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
  (let [target-file (file subdir (str (pl-utils/timestamp) ".tgz"))
        temp (str target-file ".partial")]
    (try
      (apply archive/tar temp "UTF-8" (map (juxt fs/base-name slurp) files))
      (fs/rename temp target-file)
      (doseq [filename files]
        (fs/delete filename))
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
  (doseq [subdir (remove #(already-archived? % threshold) (subdirectories dir))
          :let [files (compressible-files subdir threshold)]
          :when (seq files)]
    (compress-subdir! subdir files)))
