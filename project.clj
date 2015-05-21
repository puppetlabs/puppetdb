(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(def pdb-version "3.0.0-SNAPSHOT")

(def tk-version "1.1.1")
(def ks-version "1.0.0")

(defproject puppetlabs/pe-puppetdb-extensions "0.1.0-SNAPSHOT"
  :pedantic? :abort

  :description "Library for replicating PuppetDB instances"
  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots"  "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]
  :source-paths ["src"]
  :dependencies [[puppetlabs/puppetdb ~pdb-version]
                 [io.clj/logging "0.8.1" :exclusions [org.clojure/tools.logging
                                                      org.slf4j/slf4j-api
                                                      org.clojure/clojure]]]
  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]
  :resource-paths ["resources"]
  :profiles {:dev {:resource-paths ["test-resources"]
                   :dependencies [[org.flatland/ordered "1.5.2"]
                                  [ring-mock "0.1.5"]
                                  [puppetlabs/puppetdb ~pdb-version :classifier "test"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}
  :lein-release {:scm :git, :deploy-via :lein-deploy}

  :main ^:skip-aot puppetlabs.puppetdb.core)
