(require '[clojure.string :as s])
(use '[clojure.java.shell :only (sh)]
     '[clojure.java.io :only (file)])

(def version-string
  (memoize
   (fn []
     "Determine the version number using 'rake version -s'"
     []
     (if (.exists (file "version"))
       (s/trim (slurp "version"))
       (let [command                ["rake" "package:version" "-s"]
             {:keys [exit out err]} (apply sh command)]
         (if (zero? exit)
           (s/trim out)
           "0.0-dev-build"))))))

(def tk-version "1.1.1")
(def tk-jetty9-version "1.3.1")
(def ks-version "1.1.0")

(defproject puppetdb (version-string)
  :description "Puppet-integrated catalog and fact storage"

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort
  :plugins [[lein-cljfmt "0.2.0"]]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.3.1"]
                 [org.clojure/core.match "0.2.0-rc5"]
                 [org.clojure/math.combinatorics "0.0.4"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [puppetlabs/tools.namespace "0.2.4.1"]
                 [vimclojure/server "2.3.6" :exclusions [org.clojure/clojure]]
                 [clj-stacktrace "0.2.6"]
                 [metrics-clojure "0.7.0" :exclusions [org.clojure/clojure org.slf4j/slf4j-api]]
                 [clj-time "0.5.1"]
                 [org.clojure/java.jmx "0.2.0"]
                 ;; Filesystem utilities
                 [fs "1.1.2"]
                 ;; Version information
                 [trptcolin/versioneer "0.1.0"]
                 ;; Job scheduling
                 [overtone/at-at "1.2.0"]
                 ;; Nicer exception handling with try+/throw+
                 [slingshot "0.10.3"]

                 ;; Database connectivity
                 [com.jolbox/bonecp "0.7.1.RELEASE" :exclusions [org.slf4j/slf4j-api]]
                 [org.clojure/java.jdbc "0.1.1"]
                 [org.hsqldb/hsqldb "2.2.8"]
                 [org.postgresql/postgresql "9.2-1003-jdbc4"]
                 [clojureql "1.0.3"]
                 ;; MQ connectivity
                 [clamq/clamq-activemq "0.4" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-core "5.6.0" :exclusions [org.slf4j/slf4j-api org.fusesource.fuse-extra/fusemq-leveldb]]
                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.6"]
                 ;; WebAPI support libraries.
                 [net.cgrand/moustache "1.1.0" :exclusions [ring/ring-core org.clojure/clojure]]
                 [compojure "1.1.6"]
                 [clj-http "0.9.2"]
                 [ring/ring-core "1.2.1" :exclusions [javax.servlet/servlet-api]]
                 [org.apache.commons/commons-compress "1.8"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]
                 [prismatic/schema "0.4.0"]
                 [org.clojure/tools.macro "0.1.5"]
                 [com.novemberain/pantomime "2.1.0"]
                 [fast-zip-visit "1.0.2"]
                 [robert/hooke "1.3.0"]]

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

  :profiles {:dev {:resource-paths ["test-resources"],
                   :dependencies [[ring-mock "0.1.5"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version :classifier "test"]
                                  [org.flatland/ordered "1.5.2"]
                                  [org.clojure/test.check "0.5.8"]]}}

  :jar-exclusions [#"leiningen/"]

  :main ^:skip-aot com.puppetlabs.puppetdb.core)
