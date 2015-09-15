(ns puppetlabs.puppetdb.cli.export
  "Export utility

   This is a command-line tool for exporting data from PuppetDB.  It currently
   only supports exporting catalog data.

   The command will produce a tarball that can then be used with the companion
   `import` PuppetDB command-line tool to import data into another PuppetDB
   database."
  (:require [clj-http.client :as http-client]
            [clj-time.core :refer [now]]
            [clojure.java.io :as io]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]))

(def admin-api-version :v1)

(def cli-description "Export all PuppetDB catalog data to a backup file")

(defn- validate-cli!
  [args]
  (let [specs [["-o" "--outfile OUTFILE" "Path to backup file (required)"]
               ["-H" "--host HOST" "Hostname of PuppetDB server"
                :default "127.0.0.1"]
               ["-p" "--port PORT" "Port to connect to PuppetDB server (HTTP protocol only)"
                :default 8080
                :parse-fn #(Integer/parseInt %)]]
        required [:outfile]
        construct-base-url (fn [{:keys [host port] :as options}]
                             (-> options
                                 (assoc :base-url (utils/pdb-admin-base-url host port admin-api-version))
                                 (dissoc :host :port)))]
    (utils/try+-process-cli!
     (fn []
       (-> args
           (kitchensink/cli! specs required)
           first
           construct-base-url
           utils/validate-cli-base-url!)))))

(pls/defn-validated trigger-export-via-http!
  [base-url :- utils/base-url-schema
   filename :- s/Str]
  (-> (str (utils/base-url->str base-url) "/archive")
      (http-client/get {:accept :octet-stream :as :stream})
      :body
      (io/copy (io/file filename))))

(defn -main
  [& args]
  (let [{:keys [outfile base-url]} (validate-cli! args)]
    (println (str "Triggering export to " outfile " at " (now) "..."))
    (trigger-export-via-http! base-url outfile)
    (println (str "Finished export to " outfile " at " (now) "."))))
