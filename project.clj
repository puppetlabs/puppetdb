(def pdb-version "3.0.0-SNAPSHOT")
(def pe-pdb-version "0.1.0-SNAPSHOT")

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

(defproject puppetlabs/puppetdb pdb-version
  :description "Puppet-integrated catalog and fact storage"

  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :url "https://docs.puppetlabs.com/puppetdb/"

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [cheshire "5.4.0"]
                 [org.clojure/core.match "0.2.2"]
                 [org.clojure/math.combinatorics "0.0.4"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [puppetlabs/tools.namespace "0.2.4.1"]
                 [clj-stacktrace "0.2.8"]
                 [metrics-clojure "0.7.0" :exclusions [org.clojure/clojure org.slf4j/slf4j-api]]
                 [clj-time "0.9.0"]
                 [org.clojure/java.jmx "0.3.1"]
                 ;; Filesystem utilities
                 [me.raynes/fs "1.4.5"]
                 ;; Version information
                 [trptcolin/versioneer "0.1.0"]
                 ;; Job scheduling
                 [overtone/at-at "1.2.0"]
                 ;; Nicer exception handling with try+/throw+
                 [slingshot "0.12.2"]

                 ;; Database connectivity
                 [com.jolbox/bonecp "0.7.1.RELEASE" :exclusions [org.slf4j/slf4j-api]]
                 [org.clojure/java.jdbc "0.1.1"]
                 [org.hsqldb/hsqldb "2.2.8"]
                 [org.postgresql/postgresql "9.2-1003-jdbc4"]

                 ;; MQ connectivity
                 [org.apache.activemq/activemq-broker "5.11.1"]
                 [org.apache.activemq/activemq-kahadb-store "5.11.1"]
                 [org.apache.activemq/activemq-pool "5.11.1"]

                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.10"]
                 ;; WebAPI support libraries.
                 [net.cgrand/moustache "1.1.0" :exclusions [ring/ring-core org.clojure/clojure]]
                 [compojure "1.3.3"]
                 [clj-http "1.0.1"]
                 [ring/ring-core "1.3.2" :exclusions [javax.servlet/servlet-api]]
                 [org.apache.commons/commons-compress "1.8"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]
                 [prismatic/schema "0.4.1"]
                 [org.clojure/tools.macro "0.1.5"]
                 [com.novemberain/pantomime "2.1.0"]
                 [fast-zip-visit "1.0.2"]
                 [robert/hooke "1.3.0"]
                 [honeysql "0.5.2"]
                 [org.clojure/data.xml "0.0.8"]]

  :jvm-opts ["-XX:MaxPermSize=128M"]

  ;;The below test-selectors is basically using the PUPPETDB_DBTYPE
  ;;environment variable to be the test selector.  The selector below
  ;;will always run a test, unless it has a meta value for that
  ;;dbtype, and that value is falsey, such as
  ;;(deftest ^{:postgres false} my-test-name...)

  :test-selectors {:default (fn [test-var-meta]
                              (let [dbtype (keyword (or (System/getenv "PUPPETDB_DBTYPE")
                                                        "hsqldb"))]
                                (get test-var-meta dbtype true)))}

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5"]]
  :lein-release {:scm        :git
                 :deploy-via :lein-deploy}

  :uberjar-name "puppetdb-release.jar"
  :lein-ezbake {:vars {:user "puppetdb"
                       :group "puppetdb"
                       :build-type "foss"
                       :main-namespace "puppetlabs.puppetdb.main"
                       :create-varlib true}
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
                                  [org.clojure/test.check "0.5.9"]]}
             :ezbake {:dependencies ^:replace [[puppetlabs/puppetdb ~pdb-version]
                                               [org.clojure/tools.nrepl "0.2.3"]]
                      :name "puppetdb"
                      :plugins [[puppetlabs/lein-ezbake "0.3.9-SNAPSHOT"
                                 :exclusions [org.clojure/clojure]]]}
             :pe {:dependencies ^:replace [[puppetlabs/puppetdb ~pdb-version]
                                           [org.clojure/tools.nrepl "0.2.3"]
                                           [puppetlabs/pe-puppetdb-extensions ~pe-pdb-version]]
                  :lein-ezbake {:vars {:user "pe-puppetdb"
                                       :group "pe-puppetdb"
                                       :build-type "pe"
                                       :create-varlib true}
                                :config-dir "ext/config/pe"}
                  :version ~pdb-version
                  :name "pe-puppetdb"}
             :testutils {:source-paths ^:replace ["test"]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}

  :jar-exclusions [#"leiningen/"]

  :resource-paths ["resources" "puppet/lib" "resources/puppetlabs/puppetdb" "resources/ext/docs"]

  :main ^:skip-aot puppetlabs.puppetdb.core)
