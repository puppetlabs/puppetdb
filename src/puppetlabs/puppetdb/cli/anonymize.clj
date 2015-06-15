(ns puppetlabs.puppetdb.cli.anonymize
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.export :refer [export-metadata-file-name]]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils :refer [export-root-dir add-tar-entry]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+]])
  (:import [org.apache.commons.compress.archivers.tar TarArchiveEntry]
           [puppetlabs.puppetdb.archive TarGzReader TarGzWriter]))

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
                        "transaction_uuid" [
                                            {"context" {} "anonymize" false}
                                            ]

                        "fact-name"
                        [{"context" {"fact-name" ["architecture" "/^augeasversion.*/" "/^bios_.*/" "/^blockdevice.*/" "/^board.*/" "domain"
                                                  "facterversion" "fqdn" "hardwareisa" "hardwaremodel" "hostname" "id" "interfaces"
                                                  "/^ipaddress.*/" "/^iptables.*/" "/^ip6tables.*/" "is_pe" "is_virtual" "/^kernel.*/" "/^lsb.*/"
                                                  "/^macaddress.*/" "/^macosx.*/" "/^memoryfree.*/" "/^memorysize.*/" "memorytotal" "/^mtu_.*/"
                                                  "/^netmask.*/" "/^network.*/" "/^operatingsystem.*/" "osfamily" "path" "/^postgres_.*/"
                                                  "/^processor.*/" "/^physicalprocessorcount.*/" "productname" "ps" "puppetversion"
                                                  "rubysitedir" "rubyversion" "/^selinux.*/" "/^sp_.*/" "/^ssh.*/" "swapencrypted"
                                                  "/^swapfree.*/" "/^swapsize.*/" "timezone" "/^uptime.*/" "uuid" "virtual"]}
                          "anonymize" false}
                         {"context" {} "anonymize" true}]

                        "fact-value"
                        [{"context" {"fact-name" ["/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                          "anonymize" true}
                         {"context" {"fact-name" ["architecture" "/^augeasversion.*/" "/^bios_.*/" "/^blockdevice.*/" "/^board.*/" "facterversion"
                                                  "hardwareisa" "hardwaremodel" "id" "interfaces" "/^iptables.*/" "/^ip6tables.*/" "is_pe"
                                                  "is_virtual" "/^kernel.*/" "/^lsb.*/" "/^macosx.*/" "/^memory.*/" "/^mtu_.*/" "/^netmask.*/"
                                                  "/^operatingsystem.*/" "osfamily" "/^postgres_.*/" "/^processor.*/" "/^physicalprocessorcount.*/"
                                                  "productname" "ps" "puppetversion" "rubysitedir" "rubyversion" "/^selinux.*/"
                                                  "swapencrypted" "/^swapfree.*/" "/^swapsize.*/" "timezone" "/^uptime.*/" "virtual"]}
                          "anonymize" false}
                         {"context" {} "anonymize" true}]

                        "environment"
                        [{"context" {} "anonymize" true}]
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
                   "transaction_uuid" [
                                       {"context" {} "anonymize" false}
                                       ]

                   "fact-name"
                   [{"context" {} "anonymize" false}]

                   "fact-value" [
                                 {"context" {"fact-name" ["/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                                  "anonymize" true}
                                 {"context" {} "anonymize" false}]

                   "environment" [{"context" {} "anonymize" false}]
                   }
          }
   "none" {
           "rules" {
                    "node" [ {"context" {} "anonymize" false} ]
                    "log-message" [ {"context" {} "anonymize" false} ]
                    "type" [ {"context" {} "anonymize" false} ]
                    "title" [ {"context" {} "anonymize" false} ]
                    "parameter-name" [ {"context" {} "anonymize" false} ]
                    "line" [ {"context" {} "anonymize" false} ]
                    "file" [ {"context" {} "anonymize" false} ]
                    "message" [ {"context" {} "anonymize" false} ]
                    "parameter-value" [ {"context" {} "anonymize" false} ]
                    "transaction_uuid" [ {"context" {} "anonymize" false} ]
                    "fact-name" [{"context" {} "anonymize" false}]
                    "fact-value" [{"context" {} "anonymize" false}]
                    "environment" [{"context" {} "anonymize" false}]
                    }
           }
   })

(pls/defn-validated add-json-tar-entry
  "Add a new entry in `tar-writer` using the tar-item data structure"
  [tar-writer :- TarGzWriter
   suffix :- [String]
   contents :- {s/Any s/Any}]
  (add-tar-entry tar-writer {:file-suffix suffix
                             :contents (json/generate-pretty-string contents)}))

(defn next-json-tar-entry
  "Read and parse a JSON item from `tar-reader`"
  [tar-reader]
  (->> tar-reader
       archive/read-entry-content
       json/parse-string))

(defn add-anonymized-entity
  [tar-writer old-path entity file-name data]
  (let [file-suffix [entity file-name]
        new-path (.getPath (apply io/file export-root-dir file-suffix))
        singular-entity (case entity
                          "facts" "facts"
                          "catalogs" "catalog"
                          "reports" "report")]
    (-> "Anonymizing %s from archive entry '%s' into '%s'"
        (format singular-entity old-path new-path)
        println)
    (add-json-tar-entry tar-writer file-suffix data)))

(pls/defn-validated process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [tar-reader :- TarGzReader
   tar-entry :- TarArchiveEntry
   tar-writer :- TarGzWriter
   config
   command-versions]
  (let [path (.getName tar-entry)]
    (condp re-find path
      ;; Process catalogs
      (import/file-pattern "catalogs")
      (let [{:strs [certname] :as new-catalog} (->> (next-json-tar-entry tar-reader)
                                                    (anon/anonymize-catalog config))]
        (add-anonymized-entity tar-writer path "catalogs" (format "%s.json" certname) new-catalog))

      ;; Process reports
      (import/file-pattern "reports")
      (let [cmd-version (:store_report command-versions)
            {:strs [start_time configuration_version certname] :as new-report}
            (->> (next-json-tar-entry tar-reader)
                 (anon/anonymize-report config cmd-version))]
        (add-anonymized-entity tar-writer path "reports" (->> (str start_time configuration_version)
                                                              kitchensink/utf8-string->sha1 
                                                              (format "%s-%s.json" certname)) new-report))

      ;; Process facts
      (import/file-pattern "facts")
      (let [{:strs [certname] :as new-facts} (->> (next-json-tar-entry tar-reader)
                                                  (anon/anonymize-facts config))]
        (add-anonymized-entity tar-writer path "facts" (format "%s.json" certname) new-facts))
      nil)))

(defn- validate-cli!
  [args]
  (let [profiles (string/join ", " (keys anon-profiles))
        specs [["-o" "--outfile OUTFILE" "Path to output file (required)"]
               ["-i" "--infile INFILE" "Path to input file (required)"]
               ["-p" "--profile PROFILE" (str "Choice of anonymization profile: " profiles)
                :default "moderate"]
               ["-c" "--config CONFIG" "Configuration file path for extra profile definitions (experimental) (optional)"
                :default {}
                :parse-fn (comp clojure.edn/read-string slurp)]]
        required [:outfile :infile]]
    (utils/try+-process-cli!
     (fn []
       (first
        (kitchensink/cli! args specs required))))))

(defn -main
  [& args]
  (let [{:keys [outfile infile profile config]} (validate-cli! args)
        profile-config (-> anon-profiles
                           (merge config)
                           (get profile))
        metadata (import/parse-metadata infile)]

    (println (str "Anonymizing input data file: " infile " with profile type: " profile " to output file: " outfile))

    (with-open [tar-reader (archive/tarball-reader infile)
                tar-writer (archive/tarball-writer outfile)]
      ;; Write out the metadata first
      (add-json-tar-entry tar-writer [export-metadata-file-name] metadata)
      ;; Now process each entry
      (doseq [tar-entry (archive/all-entries tar-reader)]
        (process-tar-entry tar-reader tar-entry tar-writer profile-config (:command_versions metadata))))
    (println (str "Anonymization complete. "
                  "Check output file contents " outfile " to ensure anonymization was adequate before sharing data"))))
