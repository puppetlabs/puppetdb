(def pdb-version "5.1.2")
(def puppetserver-version "5.1.0")
(def clj-parent-version "1.4.3")

(defn deploy-info
  "Generate deployment information from the URL supplied and the username and
   password for Nexus supplied as environment variables."
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(def i18n-version "0.8.0")

(def need-permgen?
  (= "1.7" (System/getProperty "java.specification.version")))

(defproject puppetlabs/puppetdb pdb-version
  :description "Puppet-integrated catalog and fact storage"

  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :url "https://docs.puppetlabs.com/puppetdb/"

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent ~clj-parent-version]
                   :inherit [:managed-dependencies]}

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :dependencies [;; Clojure org
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async]
                 [org.clojure/core.match "0.3.0-alpha4" :exclusions [org.clojure/tools.analyzer.jvm]]
                 [org.clojure/core.memoize "0.5.9"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.clojure/tools.macro]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging]

                 ;; Puppet specific
                 [puppetlabs/comidi]
                 [puppetlabs/dujour-version-check]
                 [puppetlabs/http-client]
                 [puppetlabs/i18n]
                 [puppetlabs/kitchensink]
                 [puppetlabs/stockpile "0.0.4"]
                 [puppetlabs/tools.namespace "0.2.4.1"]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-webserver-jetty9]
                 [puppetlabs/trapperkeeper-metrics :exclusions [ring/ring-defaults org.slf4j/slf4j-api]]
                 [puppetlabs/trapperkeeper-status]

                 ;; Various
                 [cheshire]
                 [clj-stacktrace]
                 [clj-time]
                 [com.rpl/specter "0.5.7"]
                 [com.taoensso/nippy "2.10.0" :exclusions [org.clojure/tools.reader]]
                 [digest "1.4.3"]
                 [fast-zip-visit "1.0.2"]
                 [instaparse "1.4.1"]
                 [me.raynes/fs]
                 [metrics-clojure "2.6.1" :exclusions [org.clojure/clojure org.slf4j/slf4j-api]]
                 [prismatic/schema "1.1.2"]
                 [robert/hooke "1.3.0"]
                 [slingshot]
                 [trptcolin/versioneer]

                 ;; Filesystem utilities
                 [org.apache.commons/commons-lang3 "3.4"]
                 ;; Version information
                 ;; Job scheduling
                 [overtone/at-at "1.2.0"]

                 ;; Database connectivity
                 [com.zaxxer/HikariCP "2.4.3" :exclusions [org.slf4j/slf4j-api]]
                 [honeysql "0.6.3"]
                 [org.postgresql/postgresql "9.4.1208.jre7"]

                 ;; MQ connectivity
                 [org.apache.activemq/activemq-broker "5.13.2" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-kahadb-store "5.13.2" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-pool "5.13.2" :exclusions [org.slf4j/slf4j-api]]
                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.14" :exclusions [org.slf4j/slf4j-api]]

                 ;; WebAPI support libraries.
                 [bidi "2.0.12" :exclusions [org.clojure/clojurescript]]
                 [clj-http "2.0.1" :exclusions [org.apache.httpcomponents/httpcore org.apache.httpcomponents/httpclient]]
                 [com.novemberain/pantomime "2.1.0"]
                 [compojure]
                 [org.apache.commons/commons-compress "1.10"]
                 [ring/ring-core :exclusions [javax.servlet/servlet-api org.clojure/tools.reader]]]

  :jvm-opts ~(if need-permgen?
              ["-XX:MaxPermSize=200M"]
              [])

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]
            [lein-cloverage "1.0.6" :exclusions [org.clojure/clojure]]
            [lein-parent "0.3.1"]
            [puppetlabs/i18n ~i18n-version]]

  :lein-release {:scm        :git
                 :deploy-via :lein-deploy}

  :uberjar-name "puppetdb.jar"
  :lein-ezbake {:vars {:user "puppetdb"
                       :group "puppetdb"
                       :build-type "foss"
                       :main-namespace "puppetlabs.puppetdb.main"
                       :start-timeout 14400
                       :repo-target "PC1"
                       :logrotate-enabled false}
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
                   :dependencies [[ring-mock]
                                  [puppetlabs/trapperkeeper :classifier "test"]
                                  [puppetlabs/kitchensink :classifier "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 :classifier "test"]
                                  [puppetlabs/puppetserver ~puppetserver-version]
                                  [puppetlabs/puppetserver ~puppetserver-version :classifier "test"]
                                  [org.flatland/ordered "1.5.3"]
                                  [org.clojure/test.check "0.9.0"]
                                  [com.gfredericks/test.chuck "0.2.7" :exclusions
                                   [clj-time com.andrewmcveigh/cljs-time instaparse joda-time org.clojure/clojure]]
                                  [environ "1.0.2"]
                                  [riddley "0.1.12"]
                                  [io.forward/yaml "1.0.5"]

                                  ;; Only needed for :integration tests
                                  [puppetlabs/trapperkeeper-authorization nil]
                                  [puppetlabs/trapperkeeper-filesystem-watcher nil]]
                   :injections [(do
                                  (require 'schema.core)
                                  (schema.core/set-fn-validation! true))]}
             :ezbake {:dependencies ^:replace [;; NOTE: we need to explicitly pass in `nil` values
                                               ;; for the version numbers here in order to correctly
                                               ;; inherit the versions from our parent project.
                                               ;; This is because of a bug in lein 2.7.1 that
                                               ;; prevents the deps from being processed properly
                                               ;; with `:managed-dependencies` when you specify
                                               ;; dependencies in a profile.  See:
                                               ;; https://github.com/technomancy/leiningen/issues/2216
                                               ;; Hopefully we can remove those `nil`s (if we care)
                                               ;; and this comment when lein 2.7.2 is available.

                                               ;; we need to explicitly pull in our parent project's
                                               ;; clojure version here, because without it, lein
                                               ;; brings in its own version, and older versions of
                                               ;; lein depend on clojure 1.6.
                                               [org.clojure/clojure nil]

                                               ;; This circular dependency is required because of a bug in
                                               ;; ezbake (EZ-35); without it, bootstrap.cfg will not be included
                                               ;; in the final package.
                                               [puppetlabs/puppetdb ~pdb-version]
                                               [org.clojure/tools.nrepl nil]]
                      :name "puppetdb"
                      :plugins [[puppetlabs/lein-ezbake "1.6.3"]]}
             :testutils {:source-paths ^:replace ["test"]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}
             :test {:jvm-opts ~(if need-permgen?
                                 ;; integration tests cycle jruby a lot, which chews through permgen
                                 ^:replace ["-XX:MaxPermSize=500M"]
                                 [])}}

  :jar-exclusions [#"leiningen/"]

  :resource-paths ["resources" "puppet/lib" "resources/puppetlabs/puppetdb" "resources/ext/docs"]

  :main ^:skip-aot puppetlabs.puppetdb.core

  :test-selectors {:default (complement :integration)
                   :unit (complement :integration)
                   :integration :integration}

  ;; This is used to merge the locales.clj of all the dependencies into a single
  ;; file inside the uberjar
  :uberjar-merge-with {"locales.clj"  [(comp read-string slurp)
                                       (fn [new prev]
                                         (if (map? prev) [new prev] (conj prev new)))
                                       #(spit %1 (pr-str %2))]}

  :aliases {"gem" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem"
                   "--config" "./test-resources/puppetserver/puppetserver.conf"]
            "install-gems" ["trampoline" "run" "-m" "puppetlabs.puppetdb.integration.install-gems"
                            "--config" "./test-resources/puppetserver/puppetserver.conf"]})
