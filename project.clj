(defproject cmdb "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.clojure/data.json "0.1.1"]
                 [postgresql/postgresql "9.0-801.jdbc4"]
                 [org.clojure/java.jdbc "0.0.6"]
                 [digest "1.2.1"]
                 [com.h2database/h2 "1.3.159"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]
  :dev-dependencies [[marginalia "0.3.2"]
                     [midje "1.2.0"]
                     [lein-midje "1.0.3"]]
  :aot [com.puppetlabs.cmdb.core]
  :main com.puppetlabs.cmdb.core
)
