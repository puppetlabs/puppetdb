(ns puppetlabs.puppetdb.cli.anonymize
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.import :as cli-import]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+]])
  (:import [org.apache.commons.compress.archivers.tar TarArchiveEntry]
           [puppetlabs.puppetdb.archive TarGzReader TarGzWriter]))

(def cli-description "Anonymize puppetdb dump files")

(defn anonymizer-report-filename
  [{:strs [certname start_time configuration_version]}]
  (->> (str start_time configuration_version)
       kitchensink/utf8-string->sha1
       (format "%s-%s.json" certname)))

(defn add-anonymized-entity
  [tar-writer old-path entity data]
  (let [old-name (-> old-path fs/split last)
        file-name (case entity
                    ("catalogs" "facts") (format "%s.json" (get data "certname"))
                    "reports" (anonymizer-report-filename data))]
    (println (format "Anonymizing %s archive entry '%s' into '%s'" entity old-name file-name))
    (utils/add-tar-entry tar-writer {:file-suffix [entity file-name]
                                     :contents (json/generate-pretty-string data)})))

(pls/defn-validated process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [tar-reader :- TarGzReader
   tar-entry :- TarArchiveEntry
   tar-writer :- TarGzWriter
   config]
  (let [path (.getName tar-entry)]
    (condp re-find path
      (import/file-pattern "catalogs")
      (->> (utils/read-json-content tar-reader)
           (anon/anonymize-catalog config)
           (add-anonymized-entity tar-writer path "catalogs"))

      (import/file-pattern "reports")
      (->> (utils/read-json-content tar-reader)
           (anon/anonymize-report config)
           (add-anonymized-entity tar-writer path "reports"))

      (import/file-pattern "facts")
      (->> (utils/read-json-content tar-reader)
           (anon/anonymize-facts config)
           (add-anonymized-entity tar-writer path "facts"))
      nil)))

(defn- validate-cli!
  [args]
  (let [specs [["-o" "--outfile OUTFILE" "Path to output file (required)"]
               ["-i" "--infile INFILE" "Path to input file (required)"]
               ["-p" "--profile PROFILE" (str "Choice of anonymization profile: " anon/anon-profiles-str)
                :default "moderate"]]
        required [:outfile :infile]]
    (println "**WARNING** The standalone anonymization tool for PuppetDB has been deprecated."
             "Please use the PuppetDB export tool with an `--anonymization` flag instead.")
    (utils/try+-process-cli!
     (fn []
       (first
        (kitchensink/cli! args specs required))))))

(defn -main
  [& args]
  (let [{:keys [outfile infile profile]} (validate-cli! args)
        profile-config (get anon/anon-profiles profile)
        metadata (cli-import/parse-metadata infile)]

    (println (str "Anonymizing input data file: " infile " with profile type: " profile " to output file: " outfile))

    (with-open [tar-reader (archive/tarball-reader infile)
                tar-writer (archive/tarball-writer outfile)]
      ;; Write out the metadata first
      (utils/add-tar-entry tar-writer {:file-suffix [export/export-metadata-file-name]
                                       :contents (json/generate-pretty-string metadata)})
      ;; Now process each entry
      (doseq [tar-entry (archive/all-entries tar-reader)]
        (process-tar-entry tar-reader tar-entry tar-writer profile-config)))
    (println "Anonymization complete."
             (str "Check output file contents " outfile " to ensure anonymization was adequate before sharing data"))))
