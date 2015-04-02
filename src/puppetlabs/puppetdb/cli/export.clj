(ns puppetlabs.puppetdb.cli.export
  "Export utility

   This is a command-line tool for exporting data from PuppetDB.  It currently
   only supports exporting catalog data.

   The command will produce a tarball that can then be used with the companion
   `import` PuppetDB command-line tool to import data into another PuppetDB
   database."
  (:require [clj-http.client :as client]
            [clj-http.util :refer [url-encode]]
            [clj-time.core :refer [now]]
            [clojure.set :as set]
            [clojure.string :as str]
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

(def ^:private api-version :v4)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal Schemas

(def node-map {:catalog_timestamp (s/maybe String)
               :facts_timestamp (s/maybe String)
               :report_timestamp (s/maybe String)
               :catalog_environment (s/maybe String)
               :facts_environment (s/maybe String)
               :report_environment (s/maybe String)
               :certname String
               :deactivated (s/maybe String)})

(def cli-description "Export all PuppetDB catalog data to a backup file")

(def export-metadata-file-name "export-metadata.json")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General functions

(defn parse-response
  "The parsed JSON response body"
  [{:keys [status body]}]
  (when (= status 200)
    (seq (json/parse-string body true))))

(pls/defn-validated retrieve-related-data
  "Given a base-url and a specific URL retrieve the related data"
  [base-url :- utils/base-url-schema
   url :- s/Str]
  (parse-response
   (client/get
    (str (utils/base-url->str-no-path base-url) url))))

(def expanded-schema
  "Expanded schema"
  {:data s/Any
   :href s/Str})

(def unexpanded-schema
  "Unexpanded schema"
  {(s/optional-key :data) s/Any
   :href s/Str})

(pls/defn-validated complete-unexpanded :- expanded-schema
  "Complete the unexpanded data, by retrieving data from the href if data is
  missing"
  [{:keys [data href] :as value :- unexpanded-schema}
   base-url :- utils/base-url-schema]
  (if data
    value
    (assoc value :data (retrieve-related-data base-url href))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Exporting

(pls/defn-validated catalogs-for-node-query :- (s/maybe (s/pred seq? 'seq?))
  "Given a node name, retrieve the catalogs for the node."
  [node :- s/Str
   base-url :- utils/base-url-schema]
  (let [base-url (merge {:version api-version} base-url)]
    (when-let [body (parse-response
                     (client/get
                      (str (utils/base-url->str base-url)
                           "/catalogs?query="
                           (url-encode
                            (format "[\"=\",\"certname\",\"%s\"]" node)))
                      {:accept :json}))]
      (map
       #(-> %
            (update-in [:edges] complete-unexpanded base-url)
            (update-in [:resources] complete-unexpanded base-url))
       body))))

(pls/defn-validated catalogs-for-node :- (s/maybe (s/pred seq? 'seq?))
  "Given a node name, retrieve the catalogs for the node and convert
  it to the commands wire format."
  [base-url :- utils/base-url-schema
   node :- s/Str]
  (-> (catalogs-for-node-query node base-url)
      catalogs/catalogs-query->wire-v6))

(pls/defn-validated catalog->tar :- utils/tar-item
  "Create a tar-item map for the `catalog`"
  [node :- String
   catalog :- {s/Keyword s/Any}]
  {:msg (format "Writing catalog for node '%s'" node)
   :file-suffix ["catalogs" (format "%s.json" node)]
   :contents (json/generate-pretty-string catalog)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fact Exporting

(pls/defn-validated factsets-expansion :- {s/Keyword s/Any}
  "Expands known expandable fields."
  [record :- {s/Keyword s/Any}
   base-url :- utils/base-url-schema]
  (-> record
      (update-in [:facts] complete-unexpanded base-url)))

(pls/defn-validated facts-for-node-query :- (s/maybe (s/pred seq? 'seq?))
  "Retrieves the factset for a given certname `node` from `base-url`."
  [node :- s/Str
   base-url :- utils/base-url-schema]
  (let [base-url (merge {:version api-version} base-url)]
    (when-let [body (parse-response
                     (client/get
                      (str (utils/base-url->str base-url)
                           "/factsets?query="
                           (url-encode
                            (format "[\"=\",\"certname\",\"%s\"]" node)))
                      {:accept :json}))]
      (map
       factsets-expansion
       body
       (repeat base-url)))))

(pls/defn-validated facts-for-node :- (s/maybe (s/pred seq? 'seq?))
  "Retrieves the factset for a given certname, returning a compatible
  wire format."
  [base-url :- utils/base-url-schema
   node :- s/Str]
  (-> (facts-for-node-query node base-url)
      factsets/factsets-query->wire-v4))

(pls/defn-validated facts->tar :- utils/tar-item
  "Creates a tar-item map for the collection of facts"
  [node :- String
   facts :- {s/Keyword s/Any}]
  {:msg (format "Writing facts for node '%s'" node)
   :file-suffix ["facts" (format "%s.json" node)]
   :contents (json/generate-pretty-string facts)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Report Exporting

(pls/defn-validated reports-expansion :- {s/Keyword s/Any}
  [record :- {s/Keyword s/Any}
   base-url :- utils/base-url-schema]
  (-> record
      (update-in [:resource_events] complete-unexpanded base-url)))

(pls/defn-validated reports-for-node-query :- (s/maybe (s/pred seq? 'seq?))
  "Given a node name, retrieves the reports for the node."
  [node :- s/Str
   base-url :- utils/base-url-schema]
  (let [base-url (merge {:version api-version} base-url)]
    (when-let [body (parse-response
                     (client/get
                      (str (utils/base-url->str base-url)
                           "/reports?query="
                           (url-encode (format "[\"=\",\"certname\",\"%s\"]"
                                               node)))
                      {:accept :json}))]
      (map
       reports-expansion
       body
       (repeat base-url)))))

(pls/defn-validated reports-for-node :- (s/maybe (s/pred seq? 'seq?))
  "Given a node name, retrieves the reports for the node and converts it
  to the wire format."
  [base-url :- utils/base-url-schema
   node :- s/Str]
  (-> (reports-for-node-query node base-url)
      reports/reports-query->wire-v5))

(pls/defn-validated report->tar :- [utils/tar-item]
  "Create a tar-item map for the `report`"
  [node :- String
   reports :- [{:configuration_version s/Any
                :start_time s/Any
                s/Any s/Any}]]
  (mapv (fn [{:keys [configuration_version start_time] :as report}]
          (let [unique-seed (str start_time configuration_version)
                hash (kitchensink/utf8-string->sha1 unique-seed)]
            {:msg (format "Writing report for node '%s' (start-time: %s version: %s hash: %s)" node start_time configuration_version hash)
             :file-suffix ["reports" (format "%s-%s.json" node hash)]
             :contents (json/generate-pretty-string (dissoc report :hash))}))
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
  [base-url :- utils/base-url-schema
   {:keys [certname] :as node-data} :- node-map]
  {:node certname
   :facts (when-not (str/blank? (:facts_timestamp node-data))
            [(facts->tar certname (->> certname
                                       (facts-for-node base-url)
                                       first))])
   :reports (when-not (str/blank? (:report_timestamp node-data))
              (report->tar certname (->> certname
                                         (reports-for-node base-url))))
   :catalog (when-not (str/blank? (:catalog_timestamp node-data))
              [(catalog->tar certname (->> certname
                                           (catalogs-for-node base-url)
                                           first))])})

(pls/defn-validated get-nodes :- (s/maybe (s/pred seq? 'seq?))
  "Get a list of the names of all active nodes."
  [base-url :- utils/base-url-schema]
  (parse-response (client/get (str (utils/base-url->str base-url) "/nodes")
                              {:accept :json})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metadata Exporting

(pls/defn-validated ^:dynamic export-metadata :- utils/tar-item
  "Metadata about this export; used during import to ensure version compatibility."
  []
  {:msg (str "Exporting PuppetDB metadata")
   :file-suffix [export-metadata-file-name]
   :contents (json/generate-pretty-string
              {:timestamp (now)
               :command_versions
               ;; This is not ideal that we are hard-coding the command version here, but
               ;;  in our current architecture I don't believe there is any way to introspect
               ;;  on which version of the `replace catalog` matches up with the current
               ;;  version of the `catalog` endpoint... or even to query what the latest
               ;;  version of a command is.  We should improve that.
               {:replace_catalog 6
                :store_report 5
                :replace_facts 4}})})

(defn- validate-cli!
  [args]
  (let [specs    [["-o" "--outfile OUTFILE" "Path to backup file (required)"]
                  ["-H" "--host HOST" "Hostname of PuppetDB server" :default "localhost"]
                  ["-p" "--port PORT" "Port to connect to PuppetDB server (HTTP protocol only)"
                   :parse-fn #(Integer. %) :default 8080]
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
    (with-open [tar-writer (archive/tarball-writer outfile)]
      (utils/add-tar-entry tar-writer (export-metadata))
      (doseq [node nodes
              :let [node-data (get-node-data src node)]]
        (doseq [{:keys [msg] :as tar-item} (mapcat node-data [:catalog :reports :facts])]
          (println msg)
          (utils/add-tar-entry tar-writer tar-item))))))

(def -main (utils/wrap-main main))
