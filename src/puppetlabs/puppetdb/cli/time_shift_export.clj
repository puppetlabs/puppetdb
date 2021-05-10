(ns puppetlabs.puppetdb.cli.time-shift-export
  "Timestamp shift utility

   This simple command-line tool updates all the timestamps inside a PuppetDB export.
   It does this by calculating the period between the newest timestamp inside the export and the provided date.
   Then, every timestamp is shifted with that period.
   It requires three parameters:
   - Path to the PuppetDB export.
   - Date to which the newest timestamp will be shifted. If this parameter is not provided, the current
   date is used.
   - Path to where the new export will be saved. If not provided it will be saved in the same place as the
   input export but with the 'shifted' prefix."
  (:import [java.time ZonedDateTime Duration]
           [java.time.format DateTimeFormatter])
  (:require
   [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
   [puppetlabs.puppetdb.utils :as utils]
   [puppetlabs.kitchensink.core :as kitchensink]
   [clojure.java.io :as io]
   [puppetlabs.puppetdb.archive :as archive]
   [puppetlabs.puppetdb.cheshire :as json]
   [clojure.string :as str]
   [puppetlabs.puppetdb.command :refer [prep-command]]
   [puppetlabs.puppetdb.import :refer [parse-metadata]]))

(defn command-version
  [type export-metadata]
  (case type
    :catalog (get-in export-metadata [:command_versions :replace_catalog])
    :fact (get-in export-metadata [:command_versions :replace_facts])
    :report (get-in export-metadata [:command_versions :store_report])))

(defn command-string
  [type]
  (case type
    :catalog "replace catalog"
    :fact "replace facts"
    :report "store report"))

(defn parse-command-data
  [json-data type export-metadata]
  (let [command {:command (command-string type)
                 :payload json-data
                 :received (:timestamp export-metadata)
                 :version (command-version type export-metadata)}]
    (prep-command command {})))

(defn extract-tar-entry
  [tar-entry]

  (let [entry-data {:path (.getName tar-entry)}]
    (-> (condp re-find (.getName tar-entry)
          #"catalogs.*\.json$" (assoc entry-data :type :catalog)
          #"reports.*\.json$" (assoc entry-data :type :report)
          #"facts.*\.json$" (assoc entry-data :type :fact)
          #"export-metadata.json$" nil))))

(defn extract-command
  [tar-entry tar-reader export-metadata]

  (let [entry-data (extract-tar-entry tar-entry)]
    (if (:type entry-data)
      (assoc entry-data :content
        (-> tar-reader
          archive/read-entry-content
          (json/parse-string true)
          (parse-command-data (:type entry-data) export-metadata))))))

(defn get-command-versions
  [input-path]

  (with-open [tar-reader (archive/tarball-reader input-path)]
    (parse-metadata tar-reader)))

(defn calculate-newest-timestamp
  [archive-path command-versions]

  (with-open [tar-reader (archive/tarball-reader archive-path)]
    (doseq [archive-entry (archive/all-entries tar-reader)]
        (let [result (extract-command archive-entry tar-reader command-versions)]
        (do
          (println (:type result))
          (println (get-in result [:content :payload :producer_timestamp]))
          (println (type (get-in result [:content :payload :producer_timestamp])))
          (println "---------------------")
          )))))

(defn time-shift-export
  [args]

  (let [input-path      (:input args)
        export-metadata (get-command-versions input-path)]
    (calculate-newest-timestamp input-path export-metadata)))

(defn parse-time-shift-parameter
  [time-string]

  (let [parsed-time (ZonedDateTime/parse time-string)]
    (if-not parsed-time
      (utils/throw-sink-cli-error "Error: time shift date must be in UTC format!"))
    parsed-time))

(defn file-exists?
  [file_path]

  (.exists (io/file file_path)))

(defn validate-options
  [options]

  (if-not (file-exists? (:input options))
    (utils/throw-sink-cli-error "Error: input archive path must be valid!"))

  (let [parsed-time (if (:shift-to-time options)
                      (parse-time-shift-parameter (:shift-to-time options))
                      (ZonedDateTime/now))]

    {:input (:input options)
     :output (:output options)
     :shift-to-time parsed-time}))

(defn- validate-cli!
  [args]

  (let [specs    [["-i" "--input ARCHIVE" "Path to a tar.gz file containing the export to be read."]
                  ["-o" "--output ARCHIVE" "Path to a where the time shifted tar.gz file will be saved."
                   :default nil]
                  ["-t" "--time-shift-to DATE" "Reference DATE in UTC format to which all the timestamps will be shifted."]]
        required [:input]]
    (utils/try-process-cli
      (fn []
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
