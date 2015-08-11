(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(def pdb-version "3.0.3-SNAPSHOT")
(def pe-pdb-version "3.0.3-SNAPSHOT")

(def tk-version "1.1.1")
(def ks-version "1.0.0")

(defproject puppetlabs/pe-puppetdb-extensions pe-pdb-version
  :pedantic? :abort

  :description "Library for replicating PuppetDB instances"
  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots"  "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]
  :source-paths ["src"]
  :dependencies [[puppetlabs/puppetdb ~pdb-version]
                 [net.logstash.logback/logstash-logback-encoder "4.2"]
                 [io.clj/logging "0.8.1" :exclusions [org.clojure/tools.logging
                                                      org.slf4j/slf4j-api
                                                      org.clojure/clojure]]]
  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]
  :resource-paths ["resources"]

  :uberjar-name "puppetdb.jar"
  :lein-ezbake {:vars {:user "pe-puppetdb"
                       :group "pe-puppetdb"
                       :build-type "pe"
                       :main-namespace "puppetlabs.puppetdb.main"}
                :config-dir "ext/config/pe"}

  :profiles {:dev {:resource-paths ["test-resources"]
                   :dependencies [[org.flatland/ordered "1.5.2"]
                                  [ring-mock "0.1.5"]
                                  [puppetlabs/puppetdb ~pdb-version :classifier "test"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]]}
             :ezbake {:dependencies ^:replace [[puppetlabs/puppetdb ~pdb-version]
                                               [org.clojure/tools.nrepl "0.2.3"]
                                               [puppetlabs/pe-puppetdb-extensions ~pe-pdb-version]]
                      :plugins [[puppetlabs/lein-ezbake "0.3.13"
                                :exclusions [org.clojure/clojure]]]
                      :version ~pe-pdb-version
                      :name "pe-puppetdb"}}
  :lein-release {:scm :git, :deploy-via :lein-deploy}

  :main ^:skip-aot puppetlabs.puppetdb.core)
