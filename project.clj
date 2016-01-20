(def pdb-version "4.0.0-SNAPSHOT")

(defn deploy-info
  "Generate deployment information from the URL supplied and the username and
   password for Nexus supplied as environment variables."
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(def tk-version "1.1.1")
(def tk-jetty9-version "1.3.1")
(def ks-version "1.1.0")
(def tk-status-version "0.2.1")

(def pdb-jvm-opts
  (case (System/getProperty "java.specification.version")
    "1.7" ["-XX:MaxPermSize=200M"]
    []))

(defproject puppetlabs/puppetdb pdb-version
  :description "Puppet-integrated catalog and fact storage"

  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :url "https://docs.puppetlabs.com/puppetdb/"

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.4.0"]
                 [org.clojure/core.match "0.2.2"]
                 [org.clojure/math.combinatorics "0.0.4"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [puppetlabs/tools.namespace "0.2.4.1"]
                 [clj-stacktrace "0.2.8"]
                 [metrics-clojure "2.6.0" :exclusions [org.clojure/clojure org.slf4j/slf4j-api]]
                 [clj-time "0.10.0"]
                 [org.clojure/java.jmx "0.3.1"]
                 ;; Filesystem utilities
                 [me.raynes/fs "1.4.5"]
                 ;; Version information
                 [puppetlabs/dujour-version-check "0.1.3"]
                 ;; Job scheduling
                 [overtone/at-at "1.2.0"]
                 ;; Nicer exception handling with try+/throw+
                 [slingshot "0.12.2"]

                 ;; Database connectivity
                 [com.zaxxer/HikariCP "2.4.1"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [org.postgresql/postgresql "9.4-1206-jdbc4"]

                 ;; MQ connectivity
                 [org.apache.activemq/activemq-broker "5.13.0" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-kahadb-store "5.13.0" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-pool "5.13.0" :exclusions [org.slf4j/slf4j-api]]

                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.10"]
                 ;; WebAPI support libraries.
                 [compojure "1.4.0"]
                 [clj-http "2.0.0"]
                 [ring/ring-core "1.4.0" :exclusions [javax.servlet/servlet-api]]
                 [org.apache.commons/commons-compress "1.8"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]
                 [prismatic/schema "1.0.4"]
                 [puppetlabs/trapperkeeper-status ~tk-status-version :exclusions [trptcolin/versioneer]]
                 [org.clojure/tools.macro "0.1.5"]
                 [com.novemberain/pantomime "2.1.0"]
                 [fast-zip-visit "1.0.2"]
                 [robert/hooke "1.3.0"]
                 [honeysql "0.6.2"]
                 [com.rpl/specter "0.5.7"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [puppetlabs/http-client "0.4.4"]
                 [com.taoensso/nippy "2.10.0" :exclusions [org.clojure/tools.reader]]
                 [bidi "1.23.1" :exclusions [org.clojure/clojurescript]]
                 [puppetlabs/comidi "0.3.1"]]

  :jvm-opts ~pdb-jvm-opts

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5"]
            [lein-cloverage "1.0.6" :exclusions [org.clojure/clojure]]]

  :lein-release {:scm        :git
                 :deploy-via :lein-deploy}

  :uberjar-name "puppetdb.jar"
  :lein-ezbake {:vars {:user "puppetdb"
                       :group "puppetdb"
                       :build-type "foss"
                       :main-namespace "puppetlabs.puppetdb.main"
                       :repo-target "PC1"}
                :config-dir "ext/config/foss"
                }

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers  [["test" :testutils]]

  :profiles {:dev {:resource-paths ["test-resources"],
                   :dependencies [[ring-mock "0.1.5"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version :classifier "test"]
                                  [org.flatland/ordered "1.5.2"]
                                  [org.clojure/test.check "0.5.9"]
                                  [environ "1.0.0"]
                                  [org.clojure/tools.cli "0.3.0"] ; prevents dependency clash caused by lein-cloverage
                                  [riddley "0.1.10"]]}
             :ezbake {:dependencies ^:replace [[puppetlabs/puppetdb ~pdb-version]
                                               [org.clojure/tools.nrepl "0.2.3"]]
                      :name "puppetdb"
                      :plugins [[puppetlabs/lein-ezbake "0.3.21"
                                 :exclusions [org.clojure/clojure]]]}
             :testutils {:source-paths ^:replace ["test"]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}

  :jar-exclusions [#"leiningen/"]

  :resource-paths ["resources" "puppet/lib" "resources/puppetlabs/puppetdb" "resources/ext/docs"]

  :main ^:skip-aot puppetlabs.puppetdb.core)
