(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(defproject puppetdb-sync "0.1.0-SNAPSHOT"
  :description "Library for replicating PuppetDB instances"
  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots"  "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/puppetdb "3.0.0-SNAPSHOT"]]
  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  :lein-release {:scm :git, :deploy-via :lein-deploy})
