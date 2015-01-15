(ns puppetlabs.puppetdb.cli.export
  "Export utility

   This is a command-line tool for exporting data from PuppetDB.  It currently
   only supports exporting catalog data.

   The command will produce a tarball that can then be used with the companion
   `import` PuppetDB command-line tool to import data into another PuppetDB
   database."
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [fs.core :as fs]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [schema.core :as s]
            [clojure.string :as str]
            [puppetlabs.puppetdb.utils :as utils]
            [slingshot.slingshot :refer [throw+ try+]]
            [clj-time.core :refer [now]]
            [clj-http.util :refer [url-encode]]))

(def ^:private api-version :v4)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal Schemas

(def node-map {:catalog-timestamp (s/maybe String)
               :facts-timestamp (s/maybe String)
               :report-timestamp (s/maybe String)
               :catalog-environment (s/maybe String)
               :facts-environment (s/maybe String)
               :report-environment (s/maybe String)
               :certname String
               :deactivated (s/maybe String)})

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Exporting

(defn-validated catalog-for-node :- (s/maybe s/Str)
  "Given a node name, retrieve the catalog for the node."
  [base-url :- utils/base-url-schema
   node :- s/Str]
  (let [base-url (merge {:version api-version} base-url)
        src (str (utils/base-url->str base-url) "/catalogs/" (url-encode node))
        {:keys [status body]} (client/get src {:accept :json})]
    (when (= status 200) body)))

(defn-validated catalog->tar :- utils/tar-item
  "Create a tar-item map for the `catalog`"
  [node :- String
   catalog-json-str :- String]
  {:msg (format "Writing catalog for node '%s'" node)
   :file-suffix ["catalogs" (format "%s.json" node)]
   :contents catalog-json-str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fact Exporting

(defn parse-response
  "The parsed JSON response body"
  [{:keys [status body]}]
  (when (= status 200)
    (seq (json/parse-string body true))))

(defn-validated facts-for-node
  :- {s/Keyword s/Any}
  "Retrieves the factset for a given certname `node` from `base-url`."
  [base-url :- utils/base-url-schema
   node :- String]
  (let [base-url (merge {:version api-version} base-url)]
    (when-let [facts (first
                      (parse-response
                       (client/get
                        (str (utils/base-url->str base-url)
                             "/factsets?query="
                             (url-encode
                              (format "[\"=\",\"certname\",\"%s\"]" node)))
                        {:accept :json})))]
      {:name node
       :values (:facts facts)
       :environment (:environment facts)
       :producer-timestamp (:producer-timestamp facts)})))

(defn-validated facts->tar :- utils/tar-item
  "Creates a tar-item map for the collection of facts"
  [node :- String
   facts :- {s/Keyword s/Any}]
  {:msg (format "Writing facts for node '%s'" node)
   :file-suffix ["facts" (format "%s.json" node)]
   :contents (json/generate-pretty-string facts)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Report Exporting

(defn-validated events-for-report-hash :- (s/pred seq? 'seq?)
  "Given a report hash, returns all events as a vector of maps."
  [base-url :- utils/base-url-schema
   report-hash :- s/Str]
  (let [base-url (merge {:version api-version} base-url)
        body (parse-response
              (client/get
               (str (utils/base-url->str base-url)
                    "/events?query="
                    (url-encode (format "[\"=\",\"report\",\"%s\"]"
                                        report-hash)))))]
    (sort-by
     #(mapv % [:timestamp :resource-type :resource-title :property])
     (map
      #(dissoc % :report :certname :configuration-version :containing-class
               :run-start-time :run-end-time :report-receive-time :environment)
      body))))

(defn-validated reports-for-node :- (s/maybe (s/pred seq? 'seq?))
  "Given a node name, retrieves the reports for the node."
  [base-url :- utils/base-url-schema
   node :- s/Str]
  (let [base-url (merge {:version api-version} base-url)]
    (when-let [body (parse-response
                     (client/get
                      (str (utils/base-url->str base-url)
                           "/reports?query="
                           (url-encode (format "[\"=\",\"certname\",\"%s\"]"
                                               node)))
                      {:accept :json}))]
      (map
       #(dissoc % :receive-time)
       (map
        #(merge % {:resource-events (events-for-report-hash base-url (get % :hash))})
        body)))))

(defn-validated report->tar :- [utils/tar-item]
  "Create a tar-item map for the `report`"
  [node :- String
   reports :- [{:configuration-version s/Any
                :start-time s/Any
                s/Any s/Any}]]
  (mapv (fn [{:keys [configuration-version start-time] :as report}]
          (let [unique-seed (str start-time configuration-version)
                hash (kitchensink/utf8-string->sha1 unique-seed)]
            {:msg (format "Writing report for node '%s' (start-time: %s version: %s hash: %s)" node start-time configuration-version hash)
             :file-suffix ["reports" (format "%s-%s.json" node hash)]
             :contents (json/generate-pretty-string (dissoc report :hash))}))
        reports))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Node Exporting

(defn-validated get-node-data
  :- {:node String
      :facts [utils/tar-item]
      :reports [utils/tar-item]
      :catalog [utils/tar-item]}
  "Returns tar-item maps for the reports, facts and catalog of the given
   node, ready for being written to the filesystem"
  [base-url :- utils/base-url-schema
   {:keys [certname] :as node-data} :- node-map]
  {:node certname
   :facts (when-not (str/blank? (:facts-timestamp node-data))
            [(facts->tar certname (facts-for-node base-url certname))])
   :reports (when-not (str/blank? (:report-timestamp node-data))
              (report->tar certname (reports-for-node base-url certname)))
   :catalog (when-not (str/blank? (:catalog-timestamp node-data))
              [(catalog->tar certname (catalog-for-node base-url certname))])})

(defn-validated get-nodes :- (s/maybe (s/pred seq? 'seq?))
  "Get a list of the names of all active nodes."
  [base-url :- utils/base-url-schema]
  (parse-response (client/get (str (utils/base-url->str base-url) "/nodes")
                              {:accept :json})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metadata Exporting

(defn-validated ^:dynamic export-metadata :- utils/tar-item
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
               {:replace-catalog 5
                :store-report 3
                :replace-facts 3}})})

(defn- validate-cli!
  [args]
  (let [specs    [["-o" "--outfile OUTFILE" "Path to backup file (required)"]
                  ["-H" "--host HOST" "Hostname of PuppetDB server" :default "localhost"]
                  ["-p" "--port PORT" "Port to connect to PuppetDB server (HTTP protocol only)" :parse-fn #(Integer. %) :default 8080]
                  ["" "--url-prefix PREFIX" "Server prefix (HTTP protocol only)"
                   :default ""]]
        required [:outfile]]
    (try+
     (kitchensink/cli! args specs required)
     (catch map? m
       (println (:message m))
       (case (:type m)
         :puppetlabs.kitchensink.core/cli-error (System/exit 1)
         :puppetlabs.kitchensink.core/cli-help  (System/exit 0))))))

(defn- main
  [& args]
  (let [[{:keys [outfile host port url-prefix] :as opts} _] (validate-cli! args)
        src {:protocol "http" :host host :port port :prefix url-prefix}
        _ (when-let [why (utils/describe-bad-base-url src)]
            (throw+ {:type ::invalid-url :utils/exit-status 1}
                    (format "Invalid source (%s)" why)))
        nodes (get-nodes src)]
    ;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?
    (with-open [tar-writer (archive/tarball-writer outfile)]
      (utils/add-tar-entry tar-writer (export-metadata))
      (doseq [node nodes
              :let [node-data (get-node-data src node)]]
        (doseq [{:keys [msg] :as tar-item} (mapcat node-data [:catalog :reports :facts])]
          (println msg)
          (utils/add-tar-entry tar-writer tar-item))))))

(def -main (utils/wrap-main main))
