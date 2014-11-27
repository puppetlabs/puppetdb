(ns puppetlabs.puppetdb.main
  "Starts the PuppetDB service, bypassing the subcommand handling.

   Used by our main startup scripts, to avoid having to variabilize the
   sub-command within ezbake."
  (:require [puppetlabs.puppetdb.core :as core])
  (:gen-class))

(defn -main
  [& args]
  (core/run-command #(System/exit 0) #(System/exit 1) (cons "services" args)))
