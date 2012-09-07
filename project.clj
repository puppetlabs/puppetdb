(require '[clojure.string :as s])
(use '[clojure.java.shell :only (sh)]
     '[clojure.java.io :only (file)])

(def version-string
  (memoize
   (fn []
     "Determine the version number using 'git describe'"
     []
     (if (.exists (file "version"))
       (s/trim (slurp "version"))
       (let [command                ["git" "describe"]
             {:keys [exit out err]} (apply sh command)]
         (when-not (zero? exit)
           (println (format "Non-zero exit status during version check:\n%s\n%s\n%s\n%s"
                            command exit out err))
           (System/exit 1))

         ;; We just want the first 4 "components" of the version string,
         ;; joined with dots
         (-> out
           (s/trim)
           (s/replace #"-" ".")
           (s/split #"\.")
           (#(take 4 %))
           (#(s/join "." %))))))))

(defproject puppetdb (version-string)
  :description "Puppet-integrated catalog and fact storage"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [cheshire "4.0.0"]
                 [org.clojure/core.incubator "0.1.0"]
                 [org.clojure/core.match "0.2.0-alpha9"]
                 [org.clojure/core.memoize "0.5.1"]
                 [org.clojure/math.combinatorics "0.0.2"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.clojure/tools.nrepl "0.2.0-beta2"]
                 [org.clojure/tools.namespace "0.1.3"]
                 [swank-clojure "1.4.0"]
                 [clj-stacktrace "0.2.4"]
                 [metrics-clojure "0.7.0" :exclusions [org.clojure/clojure org.slf4j/slf4j-api]]
                 [clj-time "0.3.7"]
                 [org.clojure/java.jmx "0.1"]
                 ;; Filesystem utilities
                 [fs "1.1.2"]
                 ;; Configuration file parsing
                 [org.ini4j/ini4j "0.5.2"]
                 ;; Version information
                 [trptcolin/versioneer "0.1.0"]
                 ;; Nicer exception handling with try+/throw+
                 [slingshot "0.10.1"]
                 [digest "1.3.0"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 ;; Database connectivity
                 [com.jolbox/bonecp "0.7.1.RELEASE" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/slf4j-log4j12 "1.6.4"]
                 [org.clojure/java.jdbc "0.1.1"]
                 [org.hsqldb/hsqldb "2.2.8"]
                 [postgresql/postgresql "9.0-801.jdbc4"]
                 [clojureql "1.0.3"]
                 ;; MQ connectivity
                 [clamq/clamq-activemq "0.4" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-core "5.5.1" :exclusions [org.slf4j/slf4j-api]]
                 ;; WebAPI support libraries.
                 [net.cgrand/moustache "1.1.0"]
                 [clj-http "0.3.1"]
                 [ring/ring-core "1.1.1"]
                 [ring/ring-jetty-adapter "1.1.1"]]

  :dev-dependencies [[lein-marginalia "0.7.0"]
                     ;; WebAPI support libraries.
                     [ring-mock "0.1.1"]]

  :profiles {:dev {:resource-paths ["test-resources"],
                   :dependencies [[ring-mock "0.1.1"]]}}

  :jar-exclusions [#"leiningen/"]
  :manifest {"Build-Version" ~(version-string)}

  :aot [com.puppetlabs.puppetdb.core]
  :main com.puppetlabs.puppetdb.core
)
