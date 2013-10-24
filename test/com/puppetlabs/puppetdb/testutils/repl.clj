(ns com.puppetlabs.puppetdb.testutils.repl
  (:require [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as testutils]
            [fs.core :as fs]
            [com.puppetlabs.puppetdb.cli.services :as svcs]))

(defn launch-puppetdb
  "Starts a puppetdb instance with defaults from config.sample.ini.  This is useful
   for starting a puppetdb from the repl, allowing code changes without a restart.
   Specify a file path for the :config parameter to use a different config file.
   Override entries in the config with a :config-overrides map.  `config-overrides`
   is a map similar to the ones created by ini-to-map.  Keys are sections, values are
   a map with config keypairs."
  [& {:keys [config config-overrides]
      :or {config "config.sample.ini"}}]
  (let [new-config-file (testutils/temp-file "config" ".ini")
        config-path (fs/absolute-path new-config-file)]
    (println "Writing current config to" config-path)
    (utils/spit-ini new-config-file (merge-with merge (utils/ini-to-map config) config-overrides))
    (svcs/-main "--config" config-path)))

(defn launch-mem-puppetdb
  "Launches a puppetdb instance using an in-memory HSQL database and creates a
   temporary dirctory for MQ storage (vardir in the config). See launch-puppetdb
   for more info on parameters."
  [& {:keys [config config-overrides]
      :or {config "config.sample.ini"}}]
  (launch-puppetdb :config-overrides (merge-with merge
                                                 {:global {:vardir (testutils/temp-dir)}
                                                  :database (fixt/create-db-map)}
                                                 config-overrides)))

#_(def mem-puppetdb
     (future
       (launch-mem-puppetdb)))
