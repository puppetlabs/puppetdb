;; ## Export utility
;;
;; This is a command-line tool for exporting data from PuppetDB.  It currently
;; only supports exporting catalog data.
;;
;; The command will produce a tarball that can then be used with the companion
;; `import` PuppetDB command-line tool to import data into another PuppetDB
;; database.

(ns com.puppetlabs.puppetdb.cli.export
  (:use [puppetlabs.kitchensink.core :only (cli!)]
        [clj-time.core :only [now]]
        [com.puppetlabs.concurrent :only [bounded-pmap]]
        [clj-http.util :only [url-encode]])
  (:require [com.puppetlabs.cheshire :as json]
            [fs.core :as fs]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [com.puppetlabs.archive :as archive]
            [slingshot.slingshot :refer [try+]]
            [com.puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [clojure.string :as str]
            [com.puppetlabs.puppetdb.utils :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal Schemas

(def node-map {:catalog_timestamp (s/maybe String)
               :facts_timestamp (s/maybe String)
               :report_timestamp (s/maybe String)
               :name String
               :deactivated (s/maybe String)})

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Exporting

(defn catalog-for-node
  "Given a node name, retrieve the catalog for the node."
  ([host port node] (catalog-for-node host port :v4 node))
  ([host port version node]
     {:pre  [(string? host)
             (integer? port)
             (string? node)]
      :post [((some-fn string? nil?) %)]}
     (let [{:keys [status body]} (client/get
                                  (format
                                   "http://%s:%s/%s/catalogs/%s"
                                   host port (name version) node)
                                  { :accept :json})]
       (when (= status 200) body))))

(pls/defn-validated catalog->tar :- utils/tar-item
  "Create a tar-item map for the `catalog`"
  [node :- String
   catalog-json-str :- String]
  {:msg (format "Writing catalog for node '%s'" node)
   :file-suffix ["catalogs" (format "%s.json" node)]
   :contents catalog-json-str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fact Exporting

(pls/defn-validated facts-for-node
  :- {s/Keyword s/Any}
  "Given a node name, retrieve the catalog for the node."
  ([host :- String
    port :- s/Int
    node :- String]
     (facts-for-node host port :v4 node))
  ([host :- String
    port :- s/Int
    version :- s/Keyword
    node :- String]
     (let [{:keys [status body]} (client/get
                                  (format
                                   "http://%s:%s/%s/nodes/%s/facts"
                                   host port (name version) node)
                                  {:accept :json})]
       (when (= status 200)
         (let [facts (json/parse-string body true)
               facts-result {:name node
                             :values (reduce (fn [acc {:keys [name value]}]
                                               (assoc acc (keyword name) value))
                                             {} facts)}]
           (if (= :v4 version)
             (assoc facts-result :environment (:environment (first facts)))
             facts-result))))))


(pls/defn-validated facts->tar :- utils/tar-item
  "Creates a tar-item map for the collection of facts"
  [node :- String
   facts :- {s/Keyword s/Any}]
  {:msg (format "Writing facts for node '%s'" node)
   :file-suffix ["facts" (format "%s.json" node)]
   :contents (json/generate-pretty-string facts)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Report Exporting

(defn events-for-report-hash
  "Given a report hash, returns all events as a vector of maps."
  ([host port report-hash] (events-for-report-hash host port :v4 report-hash))
  ([host port version report-hash]
     {:pre  [(string? host)
             (integer? port)
             (string? report-hash)]
      :post [vector? %]}
     (let [{:keys [status body]} (client/get
                                  (format
                                   "http://%s:%s/%s/events?query=%s"
                                   host port (name version) (url-encode (format "[\"=\",\"report\",\"%s\"]" report-hash))))]
       (when
           (= status 200)
         (sort-by
          #(mapv % [:timestamp :resource-type :resource-title :property])
          (map
           #(dissoc % :report :certname :configuration-version :containing-class :run-start-time :run-end-time :report-receive-time :environment)
           (json/parse-string body true)))))))

(defn reports-for-node
  "Given a node name, retrieves the reports for the node."
  ([host port node] (reports-for-node host port :v4 node))
  ([host port version node]
     {:pre  [(string? host)
             (integer? port)
             (string? node)]
      :post [seq? %]}
     (let [{:keys [status body]} (client/get
                                  (format
                                   "http://%s:%s/%s/reports?query=%s"
                                   host port (name version) (url-encode (format "[\"=\",\"certname\",\"%s\"]" node)))
                                  {:accept :json})]
       (when
           (= status 200)
         (map
          #(dissoc % :receive-time)
          (map
           #(merge % {:resource-events (events-for-report-hash host port version (get % :hash))})
           (json/parse-string body true)))))))

(pls/defn-validated report->tar :- [utils/tar-item]
  "Create a tar-item map for the `report`"
  [node :- String
   reports :- [{:configuration-version s/Any
                :start-time s/Any
                s/Any s/Any}]]
  (mapv (fn [{:keys [configuration-version start-time] :as report}]
          {:msg (format "Writing report '%s-%s' for node '%s'" start-time configuration-version node)
           :file-suffix ["reports" (format "%s-%s-%s.json" node start-time configuration-version)]
           :contents (json/generate-pretty-string (dissoc report :hash))})
        reports))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Node Exporting

(pls/defn-validated get-node-data
  :- {:node String
      :facts [utils/tar-item]
      :reports [utils/tar-item]
      :catalog [utils/tar-item]}
  "Returns tar-item maps for the reports, facts and catalog of the given
   node, ready for being written to the filesystem"
  [host :- String
   port :- s/Int
   {:keys [name] :as node-data} :- node-map]
  {:node name
   :facts (when-not (str/blank? (:facts_timestamp node-data))
            [(facts->tar name (facts-for-node host port name))])
   :reports (when-not (str/blank? (:report_timestamp node-data))
              (report->tar name (reports-for-node host port name)))
   :catalog (when-not (str/blank? (:catalog_timestamp node-data))
              [(catalog->tar name (catalog-for-node host port name))])})

(defn get-nodes
  "Get a list of the names of all active nodes."
  [host port]
  {:pre  [(string? host)
          (integer? port)]
   :post ((some-fn nil? seq?) %)}
  (let [{:keys [status body]} (client/get
                                (format "http://%s:%s/v4/nodes" host port)
                                {:accept :json})]
    (if (= status 200)
      (json/parse-string body true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metadata Exporting

(pls/defn-validated ^:dynamic export-metadata :- utils/tar-item
  "Metadata about this export; used during import to ensure version compatibility."
  []
  {:msg (str "Exporting PuppetDB metadata")
   :file-suffix [export-metadata-file-name]
   :contents (json/generate-pretty-string
               {:timestamp (now)
                :command-versions
                ;; This is not ideal that we are hard-coding the command version here, but
                ;;  in our current architecture I don't believe there is any way to introspect
                ;;  on which version of the `replace catalog` matches up with the current
                ;;  version of the `catalog` endpoint... or even to query what the latest
                ;;  version of a command is.  We should improve that.
                {:replace-catalog 4
                 :store-report 3
                 :replace-facts 2}})})

(defn- validate-cli!
  [args]
  (let [specs    [["-o" "--outfile OUTFILE" "Path to backup file (required)"]
                  ["-H" "--host HOST" "Hostname of PuppetDB server" :default "localhost"]
                  ["-p" "--port PORT" "Port to connect to PuppetDB server (HTTP protocol only)" :parse-fn #(Integer. %) :default 8080]]
        required [:outfile]]
    (try+
      (cli! args specs required)
      (catch map? m
        (println (:message m))
        (case (:type m)
          :puppetlabs.kitchensink.core/cli-error (System/exit 1)
          :puppetlabs.kitchensink.core/cli-help  (System/exit 0))))))

(defn -main
  [& args]
  (let [[{:keys [outfile host port]} _] (validate-cli! args)
        nodes (get-nodes host port)]
    ;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?
    (with-open [tar-writer (archive/tarball-writer outfile)]
      (utils/add-tar-entry tar-writer (export-metadata))
      (doseq [node nodes
              :let [node-data (get-node-data host port node)]]
        (doseq [{:keys [msg] :as tar-item} (mapcat node-data [:catalog :reports :facts])]
          (println msg)
          (utils/add-tar-entry tar-writer tar-item))))))
