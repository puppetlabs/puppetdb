(ns puppetlabs.puppetdb.cli.version
  "Version utility

   This simple command-line tool prints a list of info about
   the version of PuppetDB.  It is useful for testing and other situations
   where you'd like to know some of the version details without having
   a running instance of PuppetDB.

   The output is currently formatted like the contents of a java properties file;
   each line contains a single property name, followed by an equals sign, followed
   by the property value."
  (:require [puppetlabs.puppetdb.meta.version :as v]
            [puppetlabs.puppetdb.scf.migrate :refer [desired-schema-version]]))

(def cli-description "Print info about the current version of PuppetDB")

;; TODO: Would like to add database info and some other things here, but that
;; will require us to have access to the configuration info.  At present, the
;; configuration parsing code is scattered throughout services.clj and not
;; cleanly accessible from here.  Perhaps we can revisit this once we've
;; refactored and cleaned up the configuration stuff a bit.
(defn -main
  [& args]
  (doseq [[key val] {"version" (v/version)
                     "target_schema_version" desired-schema-version}]
    (println (format "%s=%s" key val))))
