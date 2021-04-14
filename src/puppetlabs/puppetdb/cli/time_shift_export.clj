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

  (:require
    [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
    [puppetlabs.puppetdb.utils :as utils]
    [puppetlabs.kitchensink.core :as kitchensink]
    [clojure.java.io :as io]
    [puppetlabs.puppetdb.time :refer [to-timestamp now]]))

(defn time-shift-export
  [args]

  (println args)
  (println "Not implemented"))

(defn parse-time-shift-parameter
  [time-string]

  (let [parsed-time (to-timestamp time-string)]
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

  (let [parsed-time (if (:time-shift-to options)
                      (parse-time-shift-parameter (:time-shift-to options))
                      (now))]

    {:input (:input options)
     :output (:output options)
     :time-shift-to parsed-time}))

(defn- validate-cli!
  [args]

  (let [specs [["-i" "--input ARCHIVE" "Path to a tar.gz file containing the export to be read."]
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

  (->  args
       validate-cli!
       time-shift-export))

(defn -main
  [& args]

  (exit (run-cli-cmd #(do
                        (time-shift-export-wrapper args)
                        0))))
