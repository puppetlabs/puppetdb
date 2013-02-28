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
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [com.puppetlabs.archive :as archive]))

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")
(def export-root-dir           "puppetdb-bak")

(defn catalog-for-node
  "Given a node name, retrieve the catalog for the node.  This"
  [host port node]
  {:pre  [(string? host)
          (integer? port)
          (string? node)]
   :post [(string? %)]}
  (let [{:keys [status body]} (client/get
                                 (format
                                   "http://%s:%s/experimental/catalog/%s"
                                   host port node)
                                 { :accept :json})]
    (when (= status 200) body)))

(defn get-active-node-names
  "Get a list of the names of all active nodes."
  [host port]
  {:pre  [(string? host)
          (integer? port)]
   :post ((some-fn nil? seq?) %)}
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
  (let [specs       [["-o" "--outfile" "Path to backup file (required)"]
                     ["-H" "--host" "Hostname of PuppetDB server (defaults to 'localhost')" :default "localhost"]
                     ["-p" "--port" "Port to connect to PuppetDB server (defaults to 8080)" :default 8080]]
        required    [:outfile]
        [{:keys [outfile host port]} _] (cli! args specs required)
        nodes       (get-active-node-names host port)]
;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?

    (with-open [tar-writer (archive/tarball-writer outfile)]
      (archive/add-entry tar-writer
        (.getPath (io/file export-root-dir export-metadata-file-name))
        (json/generate-string export-metadata {:pretty true}))
      (doseq [node nodes]
        (println (format "Exporting catalog for node '%s'" node))
        (archive/add-entry tar-writer
          (.getPath (io/file export-root-dir "catalogs" (format "%s.json" node)))
          (catalog-for-node host port node))))))
