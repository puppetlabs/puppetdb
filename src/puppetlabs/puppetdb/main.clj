(ns puppetlabs.puppetdb.main
  "Starts the PuppetDB service, bypassing the subcommand handling.

   Used by our main startup scripts, to avoid having to variabilize the
   sub-command within ezbake."
  (:require
   [puppetlabs.puppetdb.core :as core]
   [puppetlabs.puppetdb.utils :refer [flush-and-exit]]))

(defn -main
  [& args]
  (core/run-command #(flush-and-exit 0)
                    #(flush-and-exit 1)
                    (cons "services" args)))
