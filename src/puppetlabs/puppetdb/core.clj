(ns puppetlabs.puppetdb.core
  "PuppetDBs normal entry point.  Dispatches to command line subcommands."
  (:require
   [clojure.string :as str]
   [puppetlabs.puppetdb.cli.util
    :refer [err-exit-status exit run-cli-cmd]]))

(def usage-lines
  ["Available subcommands:"
   "  version                 Display version information"
   "  services                Run PuppetDB"
   "  upgrade                 Upgrade to latest version and exit"
   "  benchmark               Run development-only benchmarking tool"
   "  fact-storage-benchmark"
   "  help                    Display usage summary"
   "For help on a given subcommand, invoke it with -h"])

(defn usage
  [stream]
  (binding [*out* stream]
    (println (str/join "\n" usage-lines))))

(defn help [args]
  (if (zero? (count args))
    (do (usage *out*) 0)
    (do (usage *err*) err-exit-status)))

;; Resolve the subcommands dynamically to avoid loading the world just
;; to print the version.
(defn run-resolved [cli-name fn-name args]
  (let [namespace (symbol (str "puppetlabs.puppetdb.cli." cli-name))]
    (require (vector namespace))
    (apply (ns-resolve namespace fn-name) args)))

(defn run-subcommand
  "Runs the given subcommand, which should handle shutdown and the
  process exit status itself."
  [subcommand args]
  (case subcommand
    "help" (run-cli-cmd #(help args))
    "upgrade" (run-resolved "services" 'cli [args {:upgrade-and-exit? true}])
    "services" (run-resolved "services" 'cli [args])

    ("benchmark" "fact-storage-benchmark" "version")
    (run-resolved subcommand 'cli [args])

    (do
      (usage *err*)
      err-exit-status)))

(defn -main
  [subcommand & args]
  (exit (run-subcommand subcommand args)))
