;; ## Export utility
;;
;; This is a command-line tool for exporting data from PuppetDB.  It currently
;; only supports exporting catalog data.
;;
;; The command will produce a tarball that can then be used with the companion
;; `import` PuppetDB command-line tool to import data into another PuppetDB
;; database.

(ns com.puppetlabs.puppetdb.cli.export
  (:use [com.puppetlabs.utils :only (cli!)])
  (:require [cheshire.core :as json]
            [fs.core :as fs]
            [clj-http.client :as client]))

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")
(def export-root-dir           "puppetdb-bak")

;; TODO: configure puppetdb host / port; either via --config to read the inifile,
;;   or as separate command-line args
(def host "localhost")
(def port 8080)

(defn catalog-for-node
  "Given a node name, retrieve the catalog for the node.  This"
  [node]
  ;; TODO: host/port needs to be configurable
  (let [{:keys [status body]} (client/get
                                 (format
                                   "http://%s:%s/experimental/catalog/%s"
                                   host port node)
                                 { :accept :json})]
    (when (= status 200) body)))

(defn get-active-node-names
  "Get a list of the names of all active nodes."
  []
  {:post ((some-fn nil? seq?) %)}
  (let [{:keys [status body]} (client/get
                                (format
                                  "http://%s:%s/v2/nodes"
                                  host port)
                                { :accept :json})]
    (when (= status 200)
      (map #(get % "name") (json/parse-string body)))))

(def export-metadata
  "Metadata about this export; used during import to ensure version compatibility."
  {:command-versions
    ;; This is not ideal that we are hard-coding the command version here, but
    ;;  in our current architecture I don't believe there is any way to introspect
    ;;  on which version of the `replace catalog` matches up with the current
    ;;  version of the `catalog` endpoint... or even to query what the latest
    ;;  version of a command is.  We should improve that.
    {:replace-catalog 2}})

(defn -main
  [& args]
  (let [specs       [["-o" "--outfile" "Path to backup file (required)"]]
        required    [:outfile]
        [options _] (cli! args specs required)
        nodes       (get-active-node-names)]
;; TODO: support tarball, tmp dir for extracting archive
;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?
    (let [path (fs/file (:outfile options) export-root-dir "catalogs")]
      (fs/mkdirs path)
      (doseq [node nodes]
        (let [catalog-file (fs/file path (format "%s.json" node))]
          (println "Writing catalog file: " (.getAbsolutePath catalog-file))
          (spit catalog-file(catalog-for-node node)))))
    (let [metadata-file (fs/file (:outfile options) export-root-dir export-metadata-file-name )]
      (spit metadata-file (json/generate-string export-metadata {:pretty true})))))
