(ns puppetlabs.puppetdb.cli.time-shift-export
  "Timestamp shift utility

   This simple command-line tool updates all the timestamps inside a PuppetDB export.
   It does this by calculating the period between the newest timestamp inside the export and the provided date.
   Then, every timestamp is shifted with that period.
   It accepts three parameters:
    - [Mandatory] -i / --input
      Path to the .tgz pdb export, which will be shifted.
    - [Optional] -o / --output
      Path to the where the shifted export will be saved.
      If no path is given, the shifted export is sent as a stream to standard output. You may use it like this:
      lein time-shift-export -i export.tgz -o > shifted.tgz
    - [Optional]-t / --shift-to-time
      Timestamp to which all the export timestamp will be shifted.
      If it's not provided, the system's current timestamp will be used.

    !!! All timestamps are converted to a Zero timezone format. e.g timestamps like: 2015-03-26T10:58:51+10:00
    will become 2015-03-26T11:58:51Z !!!"

  (:require
   [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
   [puppetlabs.puppetdb.utils :as utils]
   [puppetlabs.kitchensink.core :as kitchensink]
   [clojure.java.io :as io]
   [puppetlabs.puppetdb.archive :as archive]
   [puppetlabs.puppetdb.time :refer [parse-wire-datetime after? now interval plus]]
   [clj-time.coerce :as time-coerce]
   [puppetlabs.puppetdb.cheshire :as json]
   [clojure.string :as str]))

(def command-type-versions
  {"catalog" 9
   "report" 8
   "facts" 5})

(defn time-shift-timestamp
  [timestamp-string shift-period]
  (-> timestamp-string
      parse-wire-datetime
      (plus shift-period)))

(defn time-shift-resources
  [resources shift-period]
  (mapv (fn [resource]
          (-> resource
              (update :timestamp #(time-shift-timestamp % shift-period))
              (update :events (fn [events]
                                (mapv (fn [event]
                                        (update event :timestamp #(time-shift-timestamp % shift-period)))
                                      events)))))
        resources))

(defn time-shift-logs
  [logs shift-period]
  (mapv (fn [log]
          (update log :time #(time-shift-timestamp % shift-period)))
        logs))

(defn time-shift-producer-timestamp
  [json-data shift-period]
  (update json-data :producer_timestamp #(time-shift-timestamp % shift-period)))

(defn time-shift-report-timestamps
  [json-data shift-period]
  (-> json-data
      (time-shift-producer-timestamp shift-period)
      (update :start_time #(time-shift-timestamp % shift-period))
      (update :end_time #(time-shift-timestamp % shift-period))
      (update :logs #(time-shift-logs % shift-period))
      (update :resources #(time-shift-resources % shift-period))))


(defn time-shift-timestamps-from-entry
  [shift-period tar-reader tar-entry]
  (let [json-data (-> tar-reader
                      archive/read-entry-content
                      (json/parse-string true))]
    (condp #(str/includes? %2 %1) (.getName tar-entry)
      "catalogs" (time-shift-producer-timestamp json-data shift-period)
      "reports" (time-shift-report-timestamps json-data shift-period)
      "facts" (time-shift-producer-timestamp json-data shift-period)
      "export-metadata" json-data)))

(def counter (atom -1))

(defn next-command-index []
  (swap! counter inc))

(defn rename-filename
  [tar-entry format shifted-data]
  (let [file-path (.getName tar-entry)]
  (when (and (= format "stockpile") (not (str/includes? file-path "export-metadata"))  )
      (let [path-elements  (butlast (str/split file-path #"\/"))
            command-type (last path-elements)
            command-type (if (= command-type "facts") command-type (str/join "" (drop-last command-type)))
            filename (clojure.core/format "%s-%s_%s_%s_%s.json"
                                          (next-command-index)
                                          (time-coerce/to-long (:producer_timestamp shifted-data))
                                          command-type
                                          (str (get command-type-versions command-type))
                                          (:certname shifted-data))]
        (.setName tar-entry filename))))
  shifted-data)

(defn write-archive [tar-writer tar-entry format data]
  (let [is_format_stockpile (= format "stockpile")
        file-path (.getName tar-entry)]
    (when
      (or (not is_format_stockpile)
          (and is_format_stockpile
               (not (str/includes? file-path "export-metadata"))))
      (archive/add-entry tar-writer "utf-8" file-path data))))

(defn print-progress
  [index]
  (when (and (> index 0) (= (mod index 30) 0))
    (utils/print-err ".")))

(defn time-shift-input-archive
  [args]
  (let [format (:format args)]
    (utils/println-err "Started the timeshift. Please wait")
    (with-open [tar-writer (archive/tarball-writer (or (:output args) System/out))]
      (with-open [tar-reader (archive/tarball-reader (:input args))]
        (doseq [[index tar-entry] (map-indexed vector (archive/all-entries tar-reader))]
          (print-progress index)
          (->> tar-entry
               (time-shift-timestamps-from-entry (:shift-to-time args) tar-reader)
               (rename-filename tar-entry format)
               json/generate-pretty-string
               (write-archive tar-writer tar-entry format)))))))

(defn get-newest-timestamp
  [first-timestamp second-timestamp]
  (if (or (nil? second-timestamp)
          (after? first-timestamp second-timestamp))
    first-timestamp
    second-timestamp))

(defn get-resources-timestamps
  [resources]
  (mapcat (fn [resource]
            (concat [(resource :timestamp)]
                    (map #(:timestamp %) (resource :events))))
          resources))

(defn newest-timestamp-from-report
  [json-data]
  (->> (concat [(json-data :producer_timestamp)
                (json-data :start_time)
                (json-data :end_time)]
               (map #(:time %) (json-data :logs))
               (get-resources-timestamps (json-data :resources)))
       (map parse-wire-datetime)
       (reduce get-newest-timestamp)))

(defn entry-producer-timestamp
  [json-data]
  (parse-wire-datetime (:producer_timestamp json-data)))

(defn compare-command-versions
  [command-type actual-version]
  (let [expected-version (get command-type-versions command-type)]

    (when-not (= actual-version expected-version)
      (utils/println-err (str "Export " (name command-type) " command version is " actual-version " instead of " expected-version))
      (exit 2))))

(defn check-command-versions
  [json-data]
  (let [catalog-command-version (get-in json-data [:command_versions :replace_catalog])
        report-command-version (get-in json-data [:command_versions :store_report])
        fact-command-version (get-in json-data [:command_versions :replace_facts])]

    (compare-command-versions "catalog" catalog-command-version)
    (compare-command-versions "report" report-command-version)
    (compare-command-versions "facts" fact-command-version)))

(defn get-newest-timestamp-from-entry
  [tar-reader tar-entry]
  (let [json-data (-> tar-reader
                      archive/read-entry-content
                      (json/parse-string true))]
    (condp #(str/includes? %2 %1) (.getName tar-entry)
      "catalogs" (entry-producer-timestamp json-data)
      "reports" (newest-timestamp-from-report json-data)
      "facts" (entry-producer-timestamp json-data)
      "export-metadata" (check-command-versions json-data))))

(defn newest-timestamp-from-archive
  [archive-path]
  (let [newest-timestamp (atom (parse-wire-datetime "1000-01-01T11:11:11.111Z"))]

    (with-open [tar-reader (archive/tarball-reader archive-path)]
      (doseq [tar-entry (archive/all-entries tar-reader)]
        (->> tar-entry
             (get-newest-timestamp-from-entry tar-reader)
             (get-newest-timestamp @newest-timestamp)
             (reset! newest-timestamp))))
    @newest-timestamp))

(defn time-shift-export
  [args]
  (let [newest-timestamp (newest-timestamp-from-archive (:input args))
        shift-period (.toPeriod (interval newest-timestamp (:shift-to-time args)))]
    (time-shift-input-archive (assoc args :shift-to-time shift-period))))

(defn parse-time-shift-parameter
  [time-string]
  (let [parsed-time (parse-wire-datetime time-string)]
    (when-not parsed-time
      (utils/throw-sink-cli-error (str "\nError: time shift date: " time-string ", must be in UTC format!\n")))
    parsed-time))

(defn file-exists?
  [file_path]
  (.exists (io/file file_path)))

(defn validate-options
  [options]
  (when-not (file-exists? (:input options))
    (utils/throw-sink-cli-error (str "\nError: input archive path: " (:input options) ", must be valid!\n")))
  (let [parsed-time (if (:shift-to-time options)
                      (parse-time-shift-parameter (:shift-to-time options))
                      (now))]
    (assoc options :shift-to-time parsed-time)))

(defn- validate-cli!
  [args]
  (let [specs [["-i" "--input ARCHIVE" "Path to a .tgz file containing the export to be read."]
               ["-o" "--output ARCHIVE" "Path to where the time shifted .tgz file will be saved."
                :default nil]
               ["-t" "--shift-to-time DATE" "Reference DATE in UTC format to which all the timestamps will be shifted."]
               ["-f" "--format stockpile" "File names will be renamed to stockpile format and directory structure will be removed."]]
        required [:input]]
    (utils/try-process-cli (fn []
                             (-> args
                                 (kitchensink/cli! specs required)
                                 first
                                 validate-options)))))

(defn time-shift-export-wrapper
  [args]
  (-> args
      validate-cli!
      time-shift-export))

(defn -main
  [& args]
  (exit (run-cli-cmd #(do
                        (time-shift-export-wrapper args)
                        0))))
