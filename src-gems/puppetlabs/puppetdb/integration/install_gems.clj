(ns puppetlabs.puppetdb.integration.install-gems
  (:require [clojure.java.io :as io]
            [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [clojure.string :as string]))

(defn gem-run!
  [config & args]
  (let [jruby-config (jruby-puppet-core/initialize-and-create-jruby-config config)]
    (jruby-core/cli-run! jruby-config "gem" args)))

(defn install-gems [gem-list-name config _]
  (gem-run! config "install" "facter" "-v" "2.5.7")
  (gem-run! config "install" "hiera")

  ;; Install the puppetserver vendored gems listed inside its jar; this is where
  ;; ezbake gets them
  (let [gem-list (string/split (slurp (io/resource (str "ext/build-scripts/" gem-list-name))) #"\n")]
    (doseq [[gem version] (map #(string/split % #"\s") gem-list)]
      (gem-run! config "install" gem "--version" version))))

(defn -main
  [& args]
  (let [[gem-list-name & others] args]
    (cli/run (partial install-gems gem-list-name) others)))
