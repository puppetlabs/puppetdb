(ns puppetlabs.puppetdb.scf.hash-debug
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [fs.core :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query.catalogs :as qcat]
            [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]))

(defn debug-file-path
  "Creates a unique path name for the `certname`. Uses a UUID to ensure uniqueness."
  [debug-output-dir certname uuid file-name]
  (fs/file debug-output-dir (format "%s_%s_%s" certname uuid file-name)))

(defn output-clj-catalog
  "Writes `data` as a pretty-printed clojure data structure to `file-name`,
   associng it's filename to `title` for inclusion in the metadata file."
  [file-metadata title file-name data]
  (with-open [writer (io/writer file-name)]
    (pp/pprint data writer))
  (assoc file-metadata title (fs/absolute-path file-name)))

(defn output-json-catalog
  "Similar to output-clj-catalog but writes the `data` as a JSON data structure to `file-name`."
  [file-metadata title file-name data]
  (json/spit-json file-name data)
  (assoc file-metadata title (fs/absolute-path file-name)))

(defn edge-relationships->kwds
  "Updates the edge relationships from the DB to keywords for easier diffing
   with the new catalog."
  [edges]
  (map #(update-in % [:relationship] keyword) edges))

(defn resource-params->kwds
  "Updates the resource parameter keys from the DB to keywords for easier diffing
   with the new catalog."
  [resources]
  (map (fn [resource]
         (update-in resource [:parameters] #(kitchensink/mapkeys keyword %)))
       resources))

(defn query-resources
  [certname
   version]
  (let [query-sql (resources/query->sql version ["=" "certname" certname])
        results (resources/query-resources version query-sql "")]
    (:result results)))

(defn query-edges
  [certname
   version]
  (let [query-sql (edges/query->sql version ["=" "certname" certname])
        results (edges/query-edges version query-sql "")]
    (:result results)))

(defn expand-catalog-edges
  [edges
   certname
   version]
  (if (:data edges)
    edges
    (assoc edges :data (query-edges certname version))))

(defn expand-catalog-resources
  [resources
   certname
   version]
  (if (:data resources)
    resources
    (assoc resources :data (query-resources certname version))))

(defn query-catalog
  [certname
   version]
  (-> (qcat/status version certname "")
      (update-in [:resources] expand-catalog-resources certname version)
      (update-in [:edges] expand-catalog-edges certname version)))

(defn diffable-old-catalog
  "Query for the existing catalog from the DB, prep the results
   for easy diffing."
  [certname]
  (-> (query-catalog certname :v4)
      catalogs/catalog-query->wire-v6
      (update-in [:edges] edge-relationships->kwds)
      (update-in [:resources] resource-params->kwds)))

(defn debug-catalog [debug-output-dir new-hash {certname :certname new-resources :resources new-edges :edges :as catalog}]
  (let [{old-resources :resources old-edges :edges} (diffable-old-catalog certname)
        old-catalog (shash/catalog-similarity-format certname old-resources old-edges)
        new-catalog (shash/catalog-similarity-format certname (vals new-resources) new-edges)
        uuid (kitchensink/uuid)
        file-path-fn #(debug-file-path debug-output-dir certname uuid %)]

    (log/warnf "Writing catalog debugging info for %s to %s" certname debug-output-dir)
    (json/spit-json (file-path-fn "catalog-metadata.json")
                    (-> {"new catalog hash" new-hash
                         "old catalog hash" (-> old-catalog json/generate-string kitchensink/utf8-string->sha1)
                         "java version" kitchensink/java-version
                         "database name" (sutils/sql-current-connection-database-name)
                         "database version" (sutils/sql-current-connection-database-version)}
                        (output-clj-catalog "old catalog path - edn" (file-path-fn "catalog-old.edn") old-catalog)
                        (output-clj-catalog "new catalog path - edn" (file-path-fn "catalog-new.edn") new-catalog)
                        (output-json-catalog "old catalog path - json" (file-path-fn "catalog-old.json") old-catalog)
                        (output-json-catalog "new catalog path - json" (file-path-fn "catalog-new.json") new-catalog)))))
