(ns puppetlabs.puppetdb.cli.anonymize)

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
