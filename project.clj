(defproject grayskull "1.0.0-SNAPSHOT"
  :description "Puppet-integrated catalog and fact storage"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [cheshire "2.2.0"]
                 [org.clojure/core.incubator "0.1.0"]
                 [org.clojure/core.match "0.2.0-alpha9"]
                 [org.clojure/math.combinatorics "0.0.2"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/tools.cli "0.2.1"]
                 [clj-stacktrace "0.2.4"]
                 [metrics-clojure "0.5.0"]
                 [clj-time "0.3.7"]
                 ;; Filesystem utilities
                 [fs "1.1.2"]
                 ;; Configuration file parsing
                 [org.ini4j/ini4j "0.5.2"]
                 ;; Nicer exception handling with try+/throw+
                 [slingshot "0.10.1"]
                 [digest "1.3.0"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 ;; Database connectivity
                 [com.jolbox/bonecp "0.7.1.RELEASE"]
                 [org.slf4j/slf4j-log4j12 "1.5.6"]
                 [org.clojure/java.jdbc "0.1.1"]
                 [org.hsqldb/hsqldb "2.2.8"]
                 [postgresql/postgresql "9.0-801.jdbc4"]
                 [clojureql "1.0.3"]
                 ;; MQ connectivity
                 [clamq/clamq-activemq "0.4"]
                 [org.apache.activemq/activemq-core "5.5.1"]
                 ;; WebAPI support libraries.
                 [net.cgrand/moustache "1.1.0"]
                 [clj-http "0.3.1"]
                 [ring/ring-core "1.0.2"]
                 [ring/ring-jetty-adapter "1.0.2"]]

  :dev-dependencies [[lein-marginalia "0.7.0-SNAPSHOT"]
                     ;; WebAPI support libraries.
                     [ring-mock "0.1.1"]]

  :aot [com.puppetlabs.cmdb.core]
  :main com.puppetlabs.cmdb.core
)
