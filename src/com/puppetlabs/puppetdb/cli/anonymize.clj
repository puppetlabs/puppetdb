(ns com.puppetlabs.puppetdb.cli.anonymize
  (:use [com.puppetlabs.utils :only (cli!)]
        [com.puppetlabs.puppetdb.cli.export :only [export-root-dir export-metadata-file-name]]
        [com.puppetlabs.puppetdb.cli.import :only [parse-metadata]])
  (:import  [com.puppetlabs.archive TarGzReader TarGzWriter]
            [org.apache.commons.compress.archivers.tar TarArchiveEntry])
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.puppetdb.anonymizer :as anon]))

(def cli-description "Anonymize puppetdb dump files")

(def anon-profiles
  ^{:doc "Hard coded rule engine profiles indexed by profile name"}
  {
    "full" {
      ;; Full anonymization means anonymize everything
      "rules" {}
    }
    "moderate" {
      "rules" {
        "type" [
          ;; Leave the core type names alone
          {"context" {"type" [
            "Augeas" "Computer" "Cron" "Exec" "File" "Filebucket" "Group" "Host"
            "Interface" "K5login" "Macauthorization" "Mailalias" "Mcx" "Mount"
            "Notify" "Package" "Resources" "Router" "Schedule" "Schedule_task"
            "Selboolean" "Selmodule" "Service" "Ssh_authorized_key" "Sshkey" "Stage"
            "Tidy" "User" "Vlan" "Yumrepo" "Zfs" "Zone" "Zpool"]}
           "anonymize" false}
          {"context" {"type" "/^Nagios_/"} "anonymize" false}
          ;; Class
          {"context" {"type" "Class"} "anonymize" false}
          ;; Stdlib resources
          {"context" {"type" ["Anchor" "File_line"]} "anonymize" false}
          ;; PE resources, based on prefix
          {"context" {"type" "/^Pe_/"} "anonymize" false}
          ;; Some common type names from PL modules
          {"context" {"type" [
            "Firewall" "A2mod" "Vcsrepo" "Filesystem" "Logical_volume"
            "Physical_volume" "Volume_group" "Java_ks"]}
           "anonymize" false}
          {"context" {"type" [
            "/^Mysql/" "/^Postgresql/" "/^Rabbitmq/" "/^Puppetdb/" "/^Apache/"
            "/^Mrepo/" "/^F5/" "/^Apt/" "/^Registry/" "/^Concat/"]}
           "anonymize" false}
        ]
        "title" [
          ;; Leave the titles alone for some core types
          {"context"   {"type" ["Filebucket" "Package" "Stage" "Service"]}
           "anonymize" false}
        ]
        "parameter-name" [
          ;; Parameter names don't need anonymization
          {"context" {} "anonymize" false}
        ]
        "parameter-value" [
          ;; Leave some metaparameters alone
          {"context" {"parameter-name" ["provider" "ensure" "noop" "loglevel" "audit" "schedule"]}
           "anonymize" false}
          ;; Always anonymize values for parameter names with 'password' in them
          {"context" {"parameter-name" [
            "/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
           "anonymize" true}
        ]
        "line" [
          ;; Line numbers without file names does not give away a lot
          {"context" {} "anonymize" false}
        ]
      }
    }
    "low" {
      "rules" {
        "node" [
          ;; Users presumably want to hide node names more often then not
          {"context" {} "anonymize" true}
        ]
        "type" [
          {"context" {} "anonymize" false}
        ]
        "title" [
          {"context" {} "anonymize" false}
        ]
        "parameter-name" [
          {"context" {} "anonymize" false}
        ]
        "line" [
          {"context" {} "anonymize" false}
        ]
        "file" [
          {"context" {} "anonymize" false}
        ]
        "message" [
          ;; Since messages themselves may contain values, we should anonymize
          ;; any message for 'secret' parameter names
          {"context" {"parameter-name" [
            "/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
           "anonymize" true}
          {"context" {} "anonymize" false}
        ]
        "parameter-value" [
          ;; Always anonymize values for parameter names with 'password' in them
          {"context" {"parameter-name" [
            "/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
           "anonymize" true}
        ]
      }
    }
    "none" {
      "rules" {
        "node" [ {"context" {} "anonymize" false} ]
        "type" [ {"context" {} "anonymize" false} ]
        "title" [ {"context" {} "anonymize" false} ]
        "parameter-name" [ {"context" {} "anonymize" false} ]
        "line" [ {"context" {} "anonymize" false} ]
        "file" [ {"context" {} "anonymize" false} ]
        "message" [ {"context" {} "anonymize" false} ]
        "parameter-value" [ {"context" {} "anonymize" false} ]
      }
    }
  })

(defn process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [^TarGzReader tar-reader ^TarArchiveEntry tar-entry ^TarGzWriter tar-writer config]
  {:pre  [(instance? TarGzReader tar-reader)
          (instance? TarArchiveEntry tar-entry)
          (instance? TarGzWriter tar-writer)]}
  (let [path    (.getName tar-entry)
        catalog-pattern (str "^" (.getPath (io/file export-root-dir "catalogs" ".*\\.json")) "$")
        report-pattern (str "^" (.getPath (io/file export-root-dir "reports" ".*\\.json")) "$")]

    ;; Process catalogs
    (when (re-find (re-pattern catalog-pattern) path)
      (let [[_ hostname] (re-matches #".+\/(.+)\.json" path)
            newpath      (.getPath (io/file export-root-dir "catalogs" (format "%s.json" (anon/anonymize-leaf hostname :node {:node hostname} config))))]
        (println (format "Anonymizing catalog from archive entry '%s' into '%s'" path newpath))
        (archive/add-entry tar-writer "UTF-8" newpath
          (json/generate-string
            (->> tar-reader
              (archive/read-entry-content)
              (json/parse-string)
              (anon/anonymize-catalog config))
            {:pretty true}))))

    ;; Process reports
    (when (re-find (re-pattern report-pattern) path)
      (let [[_ hostname starttime confversion] (re-matches #".+\/(.+?)-(\d.+Z)-(.+)\.json" path)
            newpath     (.getPath (io/file export-root-dir "reports" (format "%s-%s-%s.json" (anon/anonymize-leaf hostname :node {:node hostname} config) starttime confversion)))]
        (println (format "Anonymizing report from archive entry '%s' to '%s'" path newpath))
        (archive/add-entry tar-writer "UTF-8" newpath
          (json/generate-string
            (->> tar-reader
              (archive/read-entry-content)
              (json/parse-string)
              (anon/anonymize-report config))
            {:pretty true}))))))

(defn -main
  [& args]
  (let [profiles       (string/join ", " (keys anon-profiles))
        specs          [["-o" "--outfile" "Path to output file (required)"]
                        ["-i" "--infile" "Path to input file (required)"]
                        ["-p" "--profile" (str "Choice of anonymization profile: " profiles) :default "moderate"]
                        ["-c" "--config" "Configuration file path for extra profile definitions (experimental) (optional)"]]
        required       [:outfile :infile]
        [{:keys [outfile infile profile config]} _] (cli! args specs required)
        extra-config   (if (empty? config) {} (read-string (slurp config)))
        profile-config (get (merge anon-profiles extra-config) profile)
        metadata       (parse-metadata infile)]

    (println (str "Anonymizing input data file: " infile " with profile type: " profile " to output file: " outfile))

    (with-open [tar-reader (archive/tarball-reader infile)]
      (with-open [tar-writer (archive/tarball-writer outfile)]
        ;; Write out the metadata first
        (archive/add-entry tar-writer "UTF-8"
          (.getPath (io/file export-root-dir export-metadata-file-name))
          (json/generate-string metadata {:pretty true}))

        ;; Now process each entry
        (doseq [tar-entry (archive/all-entries tar-reader)]
          (process-tar-entry tar-reader tar-entry tar-writer profile-config))))
    (println (str "Anonymization complete. Check output file contents " outfile " to ensure anonymization was adequate before sharing data"))))
