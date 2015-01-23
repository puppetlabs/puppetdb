(ns puppetlabs.puppetdb.cli.anonymize
  (:import [puppetlabs.puppetdb.archive TarGzReader TarGzWriter]
           [org.apache.commons.compress.archivers.tar TarArchiveEntry])
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.schema :as pls]
            [clojure.walk :refer [keywordize-keys]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.puppetdb.utils :refer [export-root-dir add-tar-entry]]
            [puppetlabs.puppetdb.cli.export :refer [export-metadata-file-name]]
            [puppetlabs.puppetdb.cli.import :refer [parse-metadata]]))

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

(defn process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [^TarGzReader tar-reader ^TarArchiveEntry tar-entry ^TarGzWriter tar-writer config metadata]
  {:pre  [(instance? TarGzReader tar-reader)
          (instance? TarArchiveEntry tar-entry)
          (instance? TarGzWriter tar-writer)]}
  (let [path    (.getName tar-entry)
        catalog-pattern (str "^" (.getPath (io/file export-root-dir "catalogs" ".*\\.json")) "$")
        report-pattern (str "^" (.getPath (io/file export-root-dir "reports" ".*\\.json")) "$")
        facts-pattern (str "^" (.getPath (io/file export-root-dir "facts" ".*\\.json")) "$")]

    ;; Process catalogs
    (when (re-find (re-pattern catalog-pattern) path)
      (let [new-catalog  (->> (next-json-tar-entry tar-reader)
                              (anon/anonymize-catalog config))
            new-hostname (get new-catalog "name")
            new-hash (shash/catalog-similarity-hash (catalogs/transform (keywordize-keys new-catalog)))
            new-catalog (assoc new-catalog "hash" new-hash)
            file-suffix  ["catalogs" (format "%s.json" new-hostname)]
            new-path     (.getPath (apply io/file export-root-dir file-suffix))]
        (println (format "Anonymizing catalog from archive entry '%s' into '%s'" path new-path))
        (add-json-tar-entry tar-writer file-suffix new-catalog)))

    ;; Process reports
    (when (re-find (re-pattern report-pattern) path)
      (let [cmd-version  (get-in metadata [:command_versions :store_report])
            new-report   (->> (next-json-tar-entry tar-reader)
                              (anon/anonymize-report config cmd-version))
            new-hostname (get new-report "certname")
            new-hash     (kitchensink/utf8-string->sha1
                          (str
                           (get new-report "start_time")
                           (get new-report "configuration_version")))
            file-suffix  ["reports" (format "%s-%s.json" new-hostname new-hash)]
            new-path     (.getPath (apply io/file export-root-dir file-suffix))]
        (println (format "Anonymizing report from archive entry '%s' to '%s'" path new-path))
        (add-json-tar-entry tar-writer file-suffix new-report)))

    ;; Process facts
    (when (re-find (re-pattern facts-pattern) path)
      (let [new-facts    (->> (next-json-tar-entry tar-reader)
                              (anon/anonymize-facts config))
            new-hostname (get new-facts "name")
            file-suffix  ["facts" (format "%s.json" new-hostname)]
            new-path     (.getPath (apply io/file export-root-dir file-suffix))]
        (println (format "Anonymizing facts from archive entry '%s' to '%s'" path new-path))
        (add-json-tar-entry tar-writer file-suffix new-facts)))))

(defn- validate-cli!
  [args]
  (let [profiles (string/join ", " (keys anon-profiles))
        specs    [["-o" "--outfile OUTFILE" "Path to output file (required)"]
                  ["-i" "--infile INFILE" "Path to input file (required)"]
                  ["-p" "--profile PROFILE" (str "Choice of anonymization profile: " profiles) :default "moderate"]
                  ["-c" "--config CONFIG" "Configuration file path for extra profile definitions (experimental) (optional)"]]
        required [:outfile :infile]]
    (try+
     (kitchensink/cli! args specs required)
     (catch map? m
       (println (:message m))
       (case (:type m)
         :puppetlabs.kitchensink.core/cli-error (System/exit 1)
         :puppetlabs.kitchensink.core/cli-help  (System/exit 0))))))

(defn -main
  [& args]
  (let [[{:keys [outfile infile profile config]} _] (validate-cli! args)
        extra-config                                (if (empty? config) {} (clojure.edn/read-string (slurp config)))
        profile-config                              (get (merge anon-profiles extra-config) profile)
        metadata                                    (parse-metadata infile)]

    (println (str "Anonymizing input data file: " infile " with profile type: " profile " to output file: " outfile))

    (with-open [tar-reader (archive/tarball-reader infile)]
      (with-open [tar-writer (archive/tarball-writer outfile)]
        ;; Write out the metadata first

        (add-json-tar-entry tar-writer [export-metadata-file-name] metadata)
        ;; Now process each entry
        (doseq [tar-entry (archive/all-entries tar-reader)]
          (process-tar-entry tar-reader tar-entry tar-writer profile-config metadata))))
    (println (str "Anonymization complete. Check output file contents " outfile " to ensure anonymization was adequate before sharing data"))))
