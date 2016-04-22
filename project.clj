(def pdb-version "4.1.0-SNAPSHOT")

(defn deploy-info
  "Generate deployment information from the URL supplied and the username and
   password for Nexus supplied as environment variables."
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(def tk-version "1.3.1")
(def tk-jetty9-version "1.5.0")
(def ks-version "1.3.0")
(def tk-status-version "0.3.1")
(def i18n-version "0.2.1")

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
                 [puppetlabs/i18n ~i18n-version]
                 [cheshire "5.5.0"]
                 [org.clojure/core.match "0.3.0-alpha4" :exclusions [org.clojure/tools.analyzer.jvm]]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/core.memoize "0.5.8"]
                 [puppetlabs/tools.namespace "0.2.4.1"]
                 [clj-stacktrace "0.2.8"]
                 [metrics-clojure "2.6.1" :exclusions [org.clojure/clojure org.slf4j/slf4j-api]]
                 [clj-time "0.11.0"]
                 ;; Filesystem utilities
                 [me.raynes/fs "1.4.6"]
                 [org.apache.commons/commons-lang3 "3.3.1"]
                 ;; Version information
                 [puppetlabs/dujour-version-check "0.1.3"]
                 ;; Job scheduling
                 [overtone/at-at "1.2.0"]
                 ;; Nicer exception handling with try+/throw+
                 [slingshot "0.12.2"]

                 ;; Database connectivity
                 [com.zaxxer/HikariCP "2.4.3"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [org.postgresql/postgresql "9.4-1206-jdbc4"]

                 ;; MQ connectivity
                 [org.apache.activemq/activemq-broker "5.13.2" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-kahadb-store "5.13.2" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-pool "5.13.2" :exclusions [org.slf4j/slf4j-api]]

                 ;; Parsing library required by PQL
                 [instaparse "1.4.1"]

                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.14" :exclusions [org.slf4j/slf4j-api]]
                 ;; WebAPI support libraries.
                 [compojure "1.4.0"]
                 [clj-http "2.0.1"]
                 [ring/ring-core "1.4.0" :exclusions [javax.servlet/servlet-api org.clojure/tools.reader]]
                 [org.apache.commons/commons-compress "1.10"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]
                 [puppetlabs/trapperkeeper-metrics "0.2.0"]
                 [prismatic/schema "1.0.4"]
                 [trptcolin/versioneer "0.2.0"]
                 [puppetlabs/trapperkeeper-status ~tk-status-version]
                 [org.clojure/tools.macro "0.1.5"]
                 [com.novemberain/pantomime "2.1.0"]
                 [fast-zip-visit "1.0.2"]
                 [robert/hooke "1.3.0"]
                 [honeysql "0.6.3"]
                 [com.rpl/specter "0.5.7"]
                 [org.clojure/core.async "0.2.374"]
                 [puppetlabs/http-client "0.5.0" :exclusions [org.apache.httpcomponents/httpclient
                                                              org.apache.httpcomponents/httpcore
                                                              org.slf4j/slf4j-api]]
                 [com.taoensso/nippy "2.10.0" :exclusions [org.clojure/tools.reader]]
                 [bidi "1.25.1" :exclusions [org.clojure/clojurescript]]
                 [puppetlabs/comidi "0.3.1"]]

  :jvm-opts ~pdb-jvm-opts

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]
            [lein-cloverage "1.0.6" :exclusions [org.clojure/clojure]]
            [puppetlabs/i18n ~i18n-version]]

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
                                  [org.flatland/ordered "1.5.3"]
                                  [org.clojure/test.check "0.5.9"]
                                  [environ "1.0.2"]
                                  [org.clojure/tools.cli "0.3.3"] ; prevents dependency clash caused by lein-cloverage
                                  [riddley "0.1.12"]]
                   :injections [(do
                                  (require 'schema.core)
                                  (schema.core/set-fn-validation! true))]}
             :ezbake {:dependencies ^:replace [[puppetlabs/puppetdb ~pdb-version]
                                               [org.clojure/tools.nrepl "0.2.3"]]
                      :name "puppetdb"
                      :plugins [[puppetlabs/lein-ezbake "0.3.24"
                                 :exclusions [org.clojure/clojure]]]}
             :testutils {:source-paths ^:replace ["test"]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}

  :jar-exclusions [#"leiningen/"]

  :resource-paths ["resources" "puppet/lib" "resources/puppetlabs/puppetdb" "resources/ext/docs"]

  :main ^:skip-aot puppetlabs.puppetdb.core)
