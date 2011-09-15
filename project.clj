(defproject cmdb "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/core.incubator "0.1.0"]
                 [digest "1.2.1"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 ;; Database connectivity
                 [com.jolbox/bonecp "0.7.1.RELEASE"]
                 [org.slf4j/slf4j-log4j12 "1.5.6"]
                 [org.clojure/java.jdbc "0.0.6"]
                 [com.h2database/h2 "1.3.159"]
                 [postgresql/postgresql "9.0-801.jdbc4"]
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
