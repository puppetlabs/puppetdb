(ns puppetlabs.puppetdb.integration.install-gems
  (:require [clojure.java.io :as io]
            [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [clojure.string :as string]
            [environ.core :refer [env]]))

(defn gem-run!
  [config & args]
  (let [jruby-config (jruby-puppet-core/initialize-and-create-jruby-config config)]
    (jruby-core/cli-run! jruby-config "gem" args)))

(defn install-gems [config _]
  ;; the puppet gem brings hiera and facter with it
  ;; TODO: it would be better to just install the puppet-agent package in CI
  (let [puppet-version  (get env :puppet-version "latest")]
    (if (not= puppet-version "latest")
      (gem-run! config "install" "puppet" "--version" puppet-version)
      (gem-run! config "install" "puppet")))

  ;; Install the puppetserver vendored gems listed inside its jar; this is where
  ;; ezbake gets them
  (let [gem-list (string/split (slurp (io/resource "ext/build-scripts/gem-list.txt")) #"\n")]
    (doseq [[gem version] (map #(string/split % #"\s") gem-list)]
      (gem-run! config "install" gem "--version" version))))

(defn -main
  [& args]
  (cli/run install-gems args))
