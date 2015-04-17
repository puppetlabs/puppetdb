(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(def pe-pdb-version "0.1.0-SNAPSHOT")
(def pdb-version "3.0.0-SNAPSHOT")

(def tk-version "1.1.1")
(def ks-version "1.0.0")

(defproject puppetlabs/pe-puppetdb-extensions pe-pdb-version
  :pedantic? :abort

  :description "Library for replicating PuppetDB instances"
  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots"  "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/puppetdb ~pdb-version]]
  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]
  :resource-paths ["resources"]
  :profiles {:dev {:resource-paths ["test-resources"],
                   :dependencies [[ring-mock "0.1.5"]
                                  [puppetlabs/puppetdb ~pdb-version :classifier "test"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]]}

             :ezbake {:dependencies ^:replace [
                                               [puppetlabs/puppetdb ~pdb-version]
                                               [puppetlabs/pe-puppetdb-extensions ~pe-pdb-version]
                                               [org.clojure/tools.nrepl "0.2.3"]]
                      :name "pe-puppetdb"
                      :plugins [[puppetlabs/lein-ezbake "0.2.2"
                                 :exclusions [org.clojure/clojure]]]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}
  :lein-release {:scm :git, :deploy-via :lein-deploy}

  :uberjar-name "puppetdb-release.jar"
  :lein-ezbake {:vars {:user "pe-puppetdb"
                       :group "pe-puppetdb"
                       :build-type "pe"
                       :main-namespace "puppetlabs.puppetdb.core"
                       :create-varlib true}
                :config-dir "ext/config"}

  :main ^:skip-aot puppetlabs.puppetdb.core)
