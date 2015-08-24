(ns puppetlabs.puppetdb.cli.export
  "Export utility

   This is a command-line tool for exporting data from PuppetDB.  It currently
   only supports exporting catalog data.

   The command will produce a tarball that can then be used with the companion
   `import` PuppetDB command-line tool to import data into another PuppetDB
   database."
  (:require [clj-http.client :as http-client]
            [clj-http.util :refer [url-encode]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.cli.anonymize :as cli-anon]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.factsets :as factsets]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import [java.net URL]))

(def admin-api-version :v1)
(def query-api-version :v4)
(def ^:private command-versions
  ;; This is not ideal that we are hard-coding the command version here, but
  ;;  in our current architecture I don't believe there is any way to introspect
  ;;  on which version of the `replace catalog` matches up with the current
  ;;  version of the `catalog` endpoint... or even to query what the latest
  ;;  version of a command is.  We should improve that.
  {:replace_catalog 6
   :store_report 5
   :replace_facts 4})

(defn query-child-href-internally
  [query-fn child-href]
  (let [[parent-str identifier child-str] (take-last 3 (str/split child-href #"/"))
        entity (case child-str
                 "metrics" :report-metrics
                 "logs" :report-logs
                 (keyword child-str))
        query (case parent-str
                "reports" (case child-str
                            ("metrics" "logs") ["=" "hash" identifier]
                            ["=" "report" identifier])
                ("factsets" "catalogs") ["=" "certname" identifier])]
    (query-fn entity query-api-version query nil doall)))

(defn maybe-expand-href
  "Expand the child-value's href if the data key isn't present in the child-value"
  [query-fn
   {:keys [href] :as child-value}]
  (utils/assoc-when child-value :data (query-child-href-internally query-fn href)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal Schemas

(def node-map {:catalog_timestamp String
               :facts_timestamp String
               :report_timestamp String
               :certname String})

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General functions

(pls/defn-validated complete-unexpanded-fields
  "Complete the unexpanded data, by retrieving data from the href if data is
  missing for the given list of fields."
  [query-fn
   fields :- [s/Keyword]
   values :- [{s/Any s/Any}]]
  (map #(kitchensink/mapvals (partial maybe-expand-href query-fn) fields %)
       values))

(pls/defn-validated data->tar :- utils/tar-item
  [entity {:keys [certname] :as data}]
  (let [json-data (json/generate-pretty-string data)
        filename (case entity
                   "facts" certname
                   "catalogs" certname
                   "reports" (format "%s-%s" certname (kitchensink/utf8-string->sha1 json-data)))]
    {:file-suffix [entity (str filename ".json")]
     :contents json-data}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Exporting

(defn catalogs-for-query
  "Given a node name, retrieve the catalogs for the node and convert
  it to the commands wire format."
  [query-fn query]
  (->> (query-fn :catalogs query-api-version query nil doall)
       (complete-unexpanded-fields query-fn [:edges :resources])
       catalogs/catalogs-query->wire-v6))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fact Exporting

(defn facts-for-query
  "Retrieves the factset for a given certname, returning a compatible
  wire format."
  [query-fn query]
  (->> (query-fn :factsets query-api-version query nil doall)
       (complete-unexpanded-fields query-fn [:facts])
       factsets/factsets-query->wire-v4))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Report Exporting

(def munge-reports-exports
  (comp (partial map #(dissoc % :hash))
        reports/reports-query->wire-v5))

(defn reports-for-query
  "Given a node name, retrieves the reports for the node and converts it
  to the wire format."
  [query-fn query]
  (->> (query-fn :reports query-api-version query nil doall)
       (complete-unexpanded-fields query-fn [:resource_events :metrics :logs])
       munge-reports-exports))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metadata Exporting

(pls/defn-validated ^:dynamic export-metadata :- utils/tar-item
  "Metadata about this export; used during import to ensure version compatibility."
  [timestamp]
  {:file-suffix [export-metadata-file-name]
   :contents (json/generate-pretty-string {:timestamp timestamp
                                           :command_versions command-versions})})

(defn- validate-cli!
  [args]
  (let [profiles (str/join ", " (keys cli-anon/anon-profiles))
        specs [["-o" "--outfile OUTFILE" "Path to backup file (required)"]
               ["-a" "--anonymization ANONYMIZATION" (str "Choice of anonymization profile: " profiles " (optional)")]
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

(defn export!
  [outfile tar-items]
  (log/info "Export triggered for PuppetDB")
  (with-open [tar-writer (archive/tarball-writer outfile)]
    (doseq [tar-item tar-items] (utils/add-tar-entry tar-writer tar-item)))
  (log/infof "Finished exporting for PuppetDB"))

(pls/defn-validated trigger-export-via-http!
  [base-url :- utils/base-url-schema
   filename :- s/Str
   anonymization]
  (let [query-params (when anonymization
                       {:query-params {"anonymization_profile" anonymization}})]
    (-> (str (utils/base-url->str base-url) "/archive")
        (http-client/get (merge {:accept :octet-stream :as :stream} query-params))
        :body
        (io/copy (io/file filename)))))

(defn -main
  [& args]
  (let [{:keys [outfile base-url anonymization]} (validate-cli! args)]
    (println (str "Triggering export to " outfile " at " (to-string (now)) "..."))
    (trigger-export-via-http! base-url outfile anonymization)
    (println (str "Finished export to " outfile " at " (to-string (now)) "."))))
