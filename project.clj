(defproject cmdb "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.clojure/data.json "0.1.2"]
                 [org.clojure/core.incubator "0.1.0"]
                 [slingshot "0.2.1"]
                 ;; This makes for much nicer code, but we can hand-code the
                 ;; decision tree later if they don't get to release in a
                 ;; timely fashion. --daniel 2011-09-19
                 [match "0.2.0-SNAPSHOT"]
                 [digest "1.2.1"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 ;; Database connectivity
                 [com.jolbox/bonecp "0.7.1.RELEASE"]
                 [org.slf4j/slf4j-log4j12 "1.5.6"]
                 [org.clojure/java.jdbc "0.0.6"]
                 [org.hsqldb/hsqldb "2.2.4"]
                 [postgresql/postgresql "9.0-801.jdbc4"]
                 ;; MQ connectivity
                 [clamq "0.3"]
                 [org.apache.activemq/activemq-core "5.5.0"]
                 ;; WebAPI support libraries.
                 [ring/ring-core            "0.3.11"]
                 [ring/ring-servlet         "0.3.11"]
                 [org.mortbay.jetty/jetty   "6.1.26"]
                 [org.clojars.daniel-pittman/clothesline "0.2.2-SNAPSHOT"]]
  :dev-dependencies [[marginalia "0.3.2"]
                     [midje "1.2.0"]
                     [lein-midje "1.0.3"]
                     ;; WebAPI support libraries.
                     [ring-mock "0.1.1"]]
  :aot [com.puppetlabs.cmdb.core]
  :main com.puppetlabs.cmdb.core
  :ring {:handler com.puppetlabs.cmdb.query/ring-handler
         :init    com.puppetlabs.cmdb.query/ring-init
         :destroy com.puppetlabs.cmdb.query/ring-destroy}
)
