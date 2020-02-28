(ns puppetlabs.puppetdb.core
  "Handles command line arguments."
  (:require
   [clojure.string :as str]
   [puppetlabs.puppetdb.cli.benchmark :as benchmark]
   [puppetlabs.puppetdb.cli.fact-storage-benchmark :as fstore-bench]
   [puppetlabs.puppetdb.cli.services :as svcs]
   [puppetlabs.puppetdb.cli.version :as cver]
   [puppetlabs.puppetdb.utils :as utils]
   [puppetlabs.trapperkeeper.logging :as logging-utils]))

(def usage-lines
  ["Available subcommands:"
   "  version                 Display version information"
   "  services                Run PuppetDB"
   "  upgrade                 Upgrade to latest version and exit"
   "  benchmark               Run development-only benchmarking tool"
   "  fact-storage-benchmark"
   "For help on a given subcommand, invoke it with -h"])

(defn usage
  [stream]
  (binding [*out* stream]
    (println (str/join "\n" usage-lines))))

(defn run-command
  "Does the real work of invoking a command by attempting to resolve it and
   passing in args. `success-fn` is a no-arg function that is called when the
   command successfully executes.  `fail-fn` is called when a bad command is given
   or a failure executing a command."
  [success-fn fail-fn [subcommand & args]]
  (let [run (case subcommand
              "version" #(apply cver/-main args)
              "services" #(svcs/provide-services args)
              "upgrade" #(svcs/provide-services args {:upgrade-and-exit? true})
              "benchmark" #(apply benchmark/-main args)
              "fact-storage-benchmark" #(apply fstore-bench/-main args)
              (do
                ;; FIXME: this should be *err*
                (usage *out*)
                (fail-fn)))]
    (utils/fail-unsupported-jdk fail-fn)
    (try
      (run)
      (success-fn)
      (catch Throwable e
        (logging-utils/catch-all-logger e)
        (fail-fn)))))

(defn -main
  [& args]
  (run-command #(utils/flush-and-exit 0) #(utils/flush-and-exit 1) args))
