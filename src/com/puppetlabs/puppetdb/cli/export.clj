;; ## Export utility
;;
;; This is a command-line tool for exporting data from PuppetDB.  It currently
;; only supports exporting catalog data.
;;
;; The command will produce a tarball that can then be used with the companion
;; `import` PuppetDB command-line tool to import data into another PuppetDB
;; database.

(ns com.puppetlabs.puppetdb.cli.export
  (:use [com.puppetlabs.utils :only (cli!)]
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-string]]
        [com.puppetlabs.concurrent :only [bounded-pmap]]
        [clj-http.util :only [url-encode]])
  (:require [cheshire.core :as json]
            [fs.core :as fs]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [com.puppetlabs.archive :as archive]))

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")
(def export-root-dir           "puppetdb-bak")

(defn catalog-for-node
  "Given a node name, retrieve the catalog for the node."
  [host port node]
  {:pre  [(string? host)
          (integer? port)
          (string? node)]
   :post [((some-fn string? nil?) %)]}
  (let [{:keys [status body]} (client/get
                                 (format
                                   "http://%s:%s/experimental/catalog/%s"
                                   host port node)
                                 { :accept :json})]
    (when (= status 200) body)))

(defn events-for-report-hash
  "Given a report hash, returns all events as a vector of maps."
  [host port report-hash]
  {:pre  [(string? host)
          (integer? port)
          (string? report-hash)]
   :post [vector? %]}
  (let [{:keys [status body]} (client/get
                                 (format
                                   "http://%s:%s/experimental/events?query=%s"
                                   host port (url-encode (format "[\"=\",\"report\",\"%s\"]" report-hash))))]
    (when
      (= status 200)
      (sort-by
        #(mapv % [:timestamp :resource-type :resource-title :property])
        (map
          #(dissoc % :report)
          (json/parse-string body true))))))

(defn reports-for-node
  "Given a node name, retrieves the reports for the node."
  [host port node]
  {:pre  [(string? host)
          (integer? port)
          (string? node)]
   :post [seq? %]}
  (let [{:keys [status body]} (client/get
                                 (format
                                   "http://%s:%s/experimental/reports?query=%s"
                                   host port (url-encode (format "[\"=\",\"certname\",\"%s\"]" node)))
                                 { :accept :json})]
    (when
      (= status 200)
      (map
        #(dissoc % :receive-time)
        (map
          #(merge % {:resource-events (events-for-report-hash host port (get % :hash))})
          (json/parse-string body true))))))

(defn get-active-node-names
  "Get a list of the names of all active nodes."
  [host port]
  {:pre  [(string? host)
          (integer? port)]
   :post ((some-fn nil? seq?) %)}
  (let [{:keys [status body]} (client/get
                                (format "http://%s:%s/v2/nodes" host port)
                                {:accept :json})]
    (if (= status 200)
      (map :name
        (filter #(not (nil? (:catalog_timestamp %)))
          (json/parse-string body true))))))

(def export-metadata
  "Metadata about this export; used during import to ensure version compatibility."
  {:timestamp (to-string (now))
   :command-versions
    ;; This is not ideal that we are hard-coding the command version here, but
    ;;  in our current architecture I don't believe there is any way to introspect
    ;;  on which version of the `replace catalog` matches up with the current
    ;;  version of the `catalog` endpoint... or even to query what the latest
    ;;  version of a command is.  We should improve that.
    {:replace-catalog 2
     :store-report 1}})

(defn get-catalog-for-node
  "Utility function for retrieving catalog data from the PuppetDB web service.
  Returns a map containing the node name and the corresponding catalog; this
  allows us to run this function against multiple nodes in parallel, and still
  be able to identify which node we've retrieved the data for when it returns."
  [host port node]
  {:pre  [(string? host)
          (integer? port)
          (string? node)]
   :post [(map? %)
          (contains? % :node)
          (contains? % :catalog)]}
  {:node    node
   :catalog (catalog-for-node host port node)})

(defn get-reports-for-node
  "Utility function for retrieving report data from the PuppetDB web service.
  Returns a map containing the node name and all the reports related to the
  node; this allows us to run this function against multiple nodes in parallel,
  and still be able to identify which node we've retrieved the data for when
  it returns."
  [host port node]
  {:pre  [(string? host)
          (integer? port)
          (string? node)]
   :post [(map? %)
          (contains? % :node)
          (string? (get % :node))
          (contains? % :reports)
          (seq? (get % :reports))]}
  {:node    node
   :reports (reports-for-node host port node)})

(defn -main
  [& args]
  (let [specs          [["-o" "--outfile" "Path to backup file (required)"]
                        ["-H" "--host" "Hostname of PuppetDB server" :default "localhost"]
                        ["-p" "--port" "Port to connect to PuppetDB server" :default 8080]]
        required       [:outfile]
        [{:keys [outfile host port]} _] (cli! args specs required)
        nodes          (get-active-node-names host port)
        get-catalog-fn (partial get-catalog-for-node host port)
        get-reports-fn (partial get-reports-for-node host port)]
;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?

    (with-open [tar-writer (archive/tarball-writer outfile)]
      (archive/add-entry tar-writer "UTF-8"
        (.getPath (io/file export-root-dir export-metadata-file-name))
        (json/generate-string export-metadata {:pretty true}))
      ;; we can use a pmap call to retrieve the catalogs in parallel, so long
      ;; as we only touch the tar stream from a single thread.  However, we need
      ;; to bound the pmap so that it doesn't overwhelm the server with requests
      ;; and use up all of the db connections.  Allowing 5 concurrent requests
      ;; seems to give us close to optimal performance w/o using too much memory.
      (doseq [{:keys [node catalog]} (bounded-pmap 5 get-catalog-fn nodes)]
        (println (format "Writing catalog for node '%s'" node))
        (archive/add-entry tar-writer "UTF-8"
          (.getPath (io/file export-root-dir "catalogs" (format "%s.json" node)))
          catalog))
      ;; Write out reports
      (doseq [{:keys [node reports]} (bounded-pmap 5 get-reports-fn nodes)]
        (doseq [report reports]
          (let [confversion (get report :configuration-version)
                starttime (get report :start-time)
                reportstr (json/generate-string (dissoc report :hash) {:pretty true})]
            (println (format "Writing report '%s-%s' for node '%s'" starttime confversion node))
            (archive/add-entry tar-writer "UTF-8"
              (.getPath (io/file export-root-dir "reports" (format "%s-%s-%s.json" node starttime confversion)))
              reportstr)))))))
