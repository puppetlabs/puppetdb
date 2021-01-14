(def pdb-version "6.13.2-SNAPSHOT")
(def clj-parent-version "4.6.11")

(defn true-in-env? [x]
  (#{"true" "yes" "1"} (System/getenv x)))

(defn pdb-run-sh [& args]
  (apply vector
         ["run" "-m" "puppetlabs.puppetdb.dev.lein/run-sh" (pr-str args)]))

(defn pdb-run-clean [paths]
  (apply pdb-run-sh {:argc #{0} :echo true} "rm" "-rf" paths))

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

(def pdb-repositories
  (if (true-in-env? "PUPPET_SUPPRESS_INTERNAL_LEIN_REPOS")
    []
    [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-releases__local/"]
     ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-snapshots__local/"]]))

;; See the integration tests section in documentation/CONTRIBUTING.md.
(def puppetserver-test-dep-ver
  (some-> (try
            (slurp (str "ext/test-conf/puppetserver-dep"))
            (catch java.io.FileNotFoundException ex
              (binding [*out* *err*]
                (println "puppetserver test dependency unconfigured (ignoring)"))
              nil))
          clojure.string/trim))

(def puppetserver-test-dep-gem-list
  (when puppetserver-test-dep-ver
    (let [[major minor] (->> (re-matches #"^([0-9]+)\.([0-9]+)\..*" puppetserver-test-dep-ver)
                             next
                             (take 2)
                             (map #(Integer/parseInt %)))]
      (if (neg? (compare [major minor] [5 3]))
        "gem-list.txt"
        "jruby-gem-list.txt"))))

(def puppetserver-test-deps
  (when puppetserver-test-dep-ver
    `[[puppetlabs/puppetserver ~puppetserver-test-dep-ver]
      [puppetlabs/puppetserver ~puppetserver-test-dep-ver :classifier "test"]]))

(def pdb-dev-deps
  (concat
   '[[ring/ring-mock]
     [puppetlabs/trapperkeeper :classifier "test"]
     [puppetlabs/kitchensink :classifier "test"]
     [puppetlabs/trapperkeeper-webserver-jetty9 :classifier "test"]
     [org.flatland/ordered "1.5.7"]
     [org.clojure/test.check "0.9.0"]
     [com.gfredericks/test.chuck "0.2.7"
      :exclusions [com.andrewmcveigh/cljs-time]]
     [environ "1.0.2"]
     [riddley "0.1.12"]
     [io.forward/yaml "1.0.5"]

     ;; Only needed for :integration tests
     [puppetlabs/trapperkeeper-filesystem-watcher nil]]
   puppetserver-test-deps))

;; Don't use lein :clean-targets so that we don't have to repeat
;; ourselves, given that we need to remove some protected files, and
;; in addition, metadata like {:protect false} doesn't appear to
;; survive profile merges.

(def pdb-clean-paths
  ["puppet/client_data"
   "puppet/client_yaml"
   "puppet/clientbucket"
   "puppet/facts.d"
   "puppet/locales"
   "puppet/preview"
   "puppet/state"
   "resources/locales.clj"
   "resources/puppetlabs/puppetdb/Messages_eo$1.class"
   "resources/puppetlabs/puppetdb/Messages_eo.class"
   "target"
   "target-gems"
   "test-resources/puppetserver/ssl/certificate_requests"
   "test-resources/puppetserver/ssl/private"])

(def pdb-distclean-paths
  (into pdb-clean-paths
        [".bundle"
         ".lein-failures"
         "Gemfile.lock"
         "ext/test-conf/pgbin-requested"
         "ext/test-conf/pgport-requested"
         "ext/test-conf/puppet-ref-requested"
         "ext/test-conf/puppetserver-dep"
         "ext/test-conf/puppetserver-ref-requested"
         "puppetserver"
         "vendor"]))

(def pdb-aot-namespaces
  (apply vector
         #"puppetlabs\.puppetdb\..*"
         (->> "resources/puppetlabs/puppetdb/bootstrap.cfg"
              clojure.java.io/reader
              line-seq
              (map clojure.string/trim)
              (remove #(re-matches #"#.*" %))  ;; # comments
              (remove #(re-matches #"puppetlabs\.puppetdb\.." %))
              (map #(clojure.string/replace % #"(.*)/[^/]+$" "$1"))
              (map symbol))))

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

  :dependencies [[org.postgresql/postgresql]
                 [org.clojure/clojure]
                 [org.clojure/core.async]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/core.memoize]
                 [org.clojure/java.jdbc]
                 [org.clojure/tools.macro]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [org.clojure/tools.logging]

                 ;; Puppet specific
                 [puppetlabs/comidi]
                 [puppetlabs/dujour-version-check]
                 [puppetlabs/i18n]
                 [puppetlabs/kitchensink]
                 [puppetlabs/stockpile "0.0.4"]
                 [puppetlabs/structured-logging]
                 [puppetlabs/tools.namespace "0.2.4.1"]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-webserver-jetty9]
                 [puppetlabs/trapperkeeper-metrics]
                 [puppetlabs/trapperkeeper-status]
                 [puppetlabs/trapperkeeper-authorization]

                 ;; Various
                 [cheshire]
                 [clj-stacktrace]
                 [clj-time]
                 [com.rpl/specter "0.5.7"]
                 [com.taoensso/nippy :exclusions [org.tukaani/xz]]
                 [digest "1.4.3"]
                 [fast-zip-visit "1.0.2"]
                 [instaparse]
                 [murphy "0.5.1"]
                 [clj-commons/fs]
                 [metrics-clojure]
                 [robert/hooke "1.3.0"]
                 [trptcolin/versioneer]
                 ;; We do not currently use this dependency directly, but
                 ;; we have documentation that shows how users can use it to
                 ;; send their logs to logstash, so we include it in the jar.
                 [net.logstash.logback/logstash-logback-encoder]
                 [com.fasterxml.jackson.core/jackson-databind]

                 ;; Filesystem utilities
                 [org.apache.commons/commons-lang3]
                 ;; Version information
                 ;; Job scheduling
                 [overtone/at-at "1.2.0"]

                 ;; Database connectivity
                 [com.zaxxer/HikariCP]
                 [honeysql]

                 ;; WebAPI support libraries.
                 [bidi]
                 [clj-http "2.0.1"]
                 [com.novemberain/pantomime "2.1.0"]
                 [compojure]
                 [ring/ring-core]

                 ;; conflict resolution
                 [org.clojure/tools.nrepl "0.2.13"]

                 ;; fixing a illegal reflective access
                 [org.tcrawley/dynapath "1.0.0"]]

  :jvm-opts ~(if need-permgen?
              ["-XX:MaxPermSize=200M"]
              [])

  :repositories ~pdb-repositories

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]
            [lein-cloverage "1.0.6"]
            [lein-parent "0.3.7"]
            [puppetlabs/i18n ~i18n-version]]

  :lein-release {:scm        :git
                 :deploy-via :lein-deploy}

  :uberjar-name "puppetdb.jar"

  ;; WARNING: these configuration options are
  ;; also set in PE's project.clj changes to things like
  ;; the main namespace and start timeout need to be updated
  ;; there as well
  :lein-ezbake {:vars {:user "puppetdb"
                       :group "puppetdb"
                       :build-type "foss"
                       :main-namespace "puppetlabs.puppetdb.cli.services"
                       :start-timeout 14400
                       :repo-target "puppet6"
                       :nonfinal-repo-target "puppet6-nightly"
                       :logrotate-enabled false}
                :config-dir "ext/config/foss"
                }

  :deploy-repositories [["releases" ~(deploy-info "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-releases__local/")]
                        ["snapshots" ~(deploy-info "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-snapshots__local/")]]

  ;; Build a puppetdb-VER-test.jar containing test/ for projects like
  ;; pdbext to use by depending on ["puppetlabs.puppetdb" :classifier
  ;; "test"].  See the :testutils profile below.
  :classifiers  {:test :testutils}

  :profiles {:defaults {:resource-paths ["test-resources"]
                        :dependencies ~pdb-dev-deps
                        :injections [(do
                                       (require 'schema.core)
                                       (schema.core/set-fn-validation! true))]}
             :dev [:defaults
                   {:dependencies [[org.bouncycastle/bcpkix-jdk15on]]}]
             :fips [:defaults
                    {:dependencies [[org.bouncycastle/bcpkix-fips]
                                    [org.bouncycastle/bc-fips]
                                    [org.bouncycastle/bctls-fips]]

                     ;; this only ensures that we run with the proper profiles
                     ;; during testing. This JVM opt will be set in the puppet module
                     ;; that sets up the JVM classpaths during installation.
                     :jvm-opts ~(let [version (System/getProperty "java.version")
                                      [major minor _] (clojure.string/split version #"\.")
                                      unsupported-ex (ex-info "Unsupported major Java version. Expects 8 or 11."
                                                       {:major major
                                                        :minor minor})]
                                  (condp = (java.lang.Integer/parseInt major)
                                    1 (if (= 8 (java.lang.Integer/parseInt minor))
                                        ["-Djava.security.properties==dev-resources/jdk8-fips-security"]
                                        (throw unsupported-ex))
                                    11 ["-Djava.security.properties==dev-resources/jdk11-fips-security"]
                                    (throw unsupported-ex)))}]
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

                                               ;; ezbake does not use the uberjar profile so we need
                                               ;; to duplicate this dependency here
                                               [org.bouncycastle/bcpkix-jdk15on nil]

                                               ;; we need to explicitly pull in our parent project's
                                               ;; clojure version here, because without it, lein
                                               ;; brings in its own version, and older versions of
                                               ;; lein depend on clojure 1.6.
                                               [org.clojure/clojure nil]

                                               ;; This circular dependency is required because of a bug in
                                               ;; ezbake (EZ-35); without it, bootstrap.cfg will not be included
                                               ;; in the final package.
                                               [puppetlabs/puppetdb ~pdb-version]]
                      :name "puppetdb"
                      :plugins [[puppetlabs/lein-ezbake "2.2.1"]]}
             :testutils {:source-paths ^:replace ["test"]
                         :resource-paths ^:replace []
                         ;; Something else may need adjustment, but
                         ;; without this, "lein uberjar" tries to
                         ;; compile test files, and crashes because
                         ;; "src" namespaces aren't available.
                         :aot ^:replace []}
             :install-gems {:source-paths ^:replace ["src-gems"]
                            :target-path "target-gems"
                            :dependencies ~puppetserver-test-deps}
             :ci {:plugins [[lein-pprint "1.1.1"]]}
             :test {:jvm-opts ~(if need-permgen?
                                 ;; integration tests cycle jruby a lot, which chews through permgen
                                 ^:replace ["-XX:MaxPermSize=500M"]
                                 [])}
             ; We only want to include bouncycastle in the FOSS uberjar.
             ; PE should be handled by selecting the proper bouncycastle jar
             ; at runtime (standard/fips)
             :uberjar {:dependencies [[org.bouncycastle/bcpkix-jdk15on]]
                       :aot ~pdb-aot-namespaces}}

  :jar-exclusions [#"leiningen/"]

  :resource-paths ["resources" "puppet/lib" "resources/puppetlabs/puppetdb" "resources/ext/docs"]

  :main puppetlabs.puppetdb.core

  :test-selectors {:default (complement :integration)
                   :unit (complement :integration)
                   :integration :integration}

  ;; This is used to merge the locales.clj of all the dependencies into a single
  ;; file inside the uberjar
  :uberjar-merge-with {"locales.clj"  [(comp read-string slurp)
                                       (fn [new prev]
                                         (if (map? prev) [new prev] (conj prev new)))
                                       #(spit %1 (pr-str %2))]}

  :aliases {"gem" ["with-profile" "install-gems,dev"
                   "trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem"
                   "--config" "./test-resources/puppetserver/puppetserver.conf"]
            "install-gems" ["with-profile" "install-gems,dev"
                            "trampoline" "run" "-m" "puppetlabs.puppetdb.integration.install-gems"
                            ~puppetserver-test-dep-gem-list
                            "--config" "./test-resources/puppetserver/puppetserver.conf"]
            "clean" ~(pdb-run-clean pdb-clean-paths)
            "distclean" ~(pdb-run-clean pdb-distclean-paths)})
