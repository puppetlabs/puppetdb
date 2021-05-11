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
   [puppetlabs.puppetdb.import :refer [parse-metadata]]))

(defn shift-timestamp
  [timestamp-string period & h-format]

  (let [format (if (nil? h-format)
                 "yyyy-MM-dd'T'HH:mm:ss.SSSz"
                 "yyyy-MM-dd'T'HH:mm:ss.SSSxxx")]
    (if-not (nil? timestamp-string)
      (.format (DateTimeFormatter/ofPattern format) (.plus (ZonedDateTime/parse timestamp-string) period))
      nil)))

(defn timeshift-resources
  [resources shift-interval]

  (mapv
    (fn [resource]
      (-> resource
        (update :timestamp #(shift-timestamp % shift-interval true))
        (update :events (fn [events]
                          (mapv
                            (fn [event]
                              (update event :timestamp #(shift-timestamp % shift-interval true)))
                            events)))))
    resources))

(defn timeshift-logs
  [logs shift-interval]

  (mapv
    (fn [log]
      (update log :time #(shift-timestamp % shift-interval true)))
    logs))

(defn shift-report-timestamps
  [parsed-data shift-interval]

  (update parsed-data :content (fn [content]
                                 (-> content
                                   (update :producer_timestamp #(shift-timestamp % shift-interval))
                                   (update :start_time #(shift-timestamp % shift-interval))
                                   (update :end_time #(shift-timestamp % shift-interval))
                                   (update :logs #(timeshift-logs % shift-interval))
                                   (update :resources #(timeshift-resources % shift-interval))))))

(defn shift-fact-timestamps
  [parsed-data shift-interval]

  (update-in parsed-data [:content :producer_timestamp] #(shift-timestamp % shift-interval)))

(defn shift-catalog-timestamps
  [parsed-data shift-interval]

  (update-in parsed-data [:content :producer_timestamp] #(shift-timestamp % shift-interval)))

(defn shift-timestamps-from-entry
  [parsed-data shift-interval]

  (let [data-type (:type parsed-data)]
    (cond
      (= :catalog data-type) (shift-catalog-timestamps parsed-data shift-interval)
      (= :fact data-type) (shift-fact-timestamps parsed-data shift-interval)
      (= :report data-type) (shift-report-timestamps parsed-data shift-interval)
      :else parsed-data)))

(defn extract-tar-entry
  [tar-entry tar-reader]

  (let [parsed-entry (-> tar-reader
                       archive/read-entry-content
                       (json/parse-string true))
        file-path    (.getName tar-entry)
        parsed-data  {:path file-path, :content parsed-entry}]
    (condp re-find (.getName tar-entry)
      #"catalogs.*\.json$" (assoc parsed-data :type :catalog)
      #"reports.*\.json$" (assoc parsed-data :type :report)
      #"facts.*\.json$" (assoc parsed-data :type :fact)
      #"export-metadata.json$" (assoc parsed-data :type :metadata))))

(defn timeshift-input-archive
  [output-path input-path interval-period]

  (with-open [tar-writer (archive/tarball-writer output-path)]
    (with-open [tar-reader (archive/tarball-reader input-path)]
      (doseq [archive-entry (archive/all-entries tar-reader)]
        (let [shifted-entry (-> archive-entry
                              (extract-tar-entry tar-reader)
                              (shift-timestamps-from-entry interval-period))]
          (archive/add-entry tar-writer "utf-8" (:path shifted-entry) (json/generate-pretty-string (:content shifted-entry))))))))

(defn set-output-archive-path
  [output-path input-path]

  (if (nil? output-path)
    (let [input-path  input-path
          path-tokens (str/split input-path #"/")
          file-name   (str "shifted-" (last path-tokens))]
      (str/join "/" (conj (pop path-tokens) file-name)))
    output-path))

(def newest-timestamp (atom nil))

(defn set-newest-timestamp
  [timestamp]

  (if (and
        (not (nil? timestamp))
        (or (nil? @newest-timestamp) (.isAfter timestamp @newest-timestamp)))
    (reset! newest-timestamp timestamp)))

(defn update-newest-timestamp
  [timestamp-string]

  (-> (ZonedDateTime/parse timestamp-string)
    (set-newest-timestamp)))

(defn set-newest-timestamp-from-report
  [json-data]

  (update-newest-timestamp (json-data :start_time))
  (update-newest-timestamp (json-data :end_time))

  (doseq [log (json-data :logs)]
    (update-newest-timestamp (log :time)))

  (doseq [resource (json-data :resources)]
    (update-newest-timestamp (resource :timestamp))
    (doseq [event (resource :events)]
      (update-newest-timestamp (event :timestamp)))))

(defn get-newest-timestamp-from-entry
  [parsed-data]

  (let [data-type (:type parsed-data)]
    (if (some #{data-type} [:catalog :fact :report])
      (update-newest-timestamp ((:content parsed-data) :producer_timestamp)))
    (if (= :report data-type)
      (set-newest-timestamp-from-report (:content parsed-data)))))

(defn calculate-newest-timestamp
  [archive-path]

  (with-open [tar-reader (archive/tarball-reader archive-path)]
    (doseq [archive-entry (archive/all-entries tar-reader)]
      (-> archive-entry
        (extract-tar-entry tar-reader)
        (get-newest-timestamp-from-entry)))))

(defn compare-command-versions
  [actual-version expected-version command-type]

  (if (not= expected-version actual-version)
    (println (str "Export " command-type " command version is " actual-version " instead of " expected-version))))

(defn check-command-versions
  [input-path]

  (let [command-versions        (:command_versions (with-open [tar-reader (archive/tarball-reader input-path)]
                                                     (parse-metadata tar-reader)))
        catalog-command-version (:replace_catalog command-versions)
        report-command-version  (:store_report command-versions)
        fact-command-version    (:replace_facts command-versions)]

    (compare-command-versions catalog-command-version 9 "catalog")
    (compare-command-versions report-command-version 8 "report")
    (compare-command-versions fact-command-version 5 "fact")))

(defn time-shift-export
  [args]

  (let [input-path (:input args)]
    (check-command-versions input-path)
    (calculate-newest-timestamp input-path)

    (let [shift-interval (Duration/between @newest-timestamp (:shift-to-time args))
          output-path    (set-output-archive-path (:output args) input-path)]
      (timeshift-input-archive output-path input-path shift-interval))))

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
