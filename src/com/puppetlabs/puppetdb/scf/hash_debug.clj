(ns com.puppetlabs.puppetdb.scf.hash-debug
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.catalogs :as qcat]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [com.puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [fs.core :as fs]))

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
  "Updates the reousrce parameter keys from the DB to keywords for easier diffing
   with the new catalog."
  [resources]
  (map (fn [resource]
         (update-in resource [:parameters] #(kitchensink/mapkeys keyword %)))
       resources))

(defn diffable-old-catalog
  "Query for the existing catalog from the DB, prep the results
   for easy diffing."
  [certname]
  (-> (qcat/catalog-for-node :latest certname)
      (update-in [:edges] edge-relationships->kwds)
      (update-in [:resources] resource-params->kwds)))

(defn debug-catalog [debug-output-dir new-hash {certname :name new-resources :resources new-edges :edges :as catalog}]
  (let [{old-resources :resources old-edges :edges} (diffable-old-catalog certname)
        old-catalog (shash/catalog-similarity-format certname old-resources old-edges)
        new-catalog (shash/catalog-similarity-format certname (vals new-resources) new-edges)
        uuid (kitchensink/uuid)
        file-path-fn #(debug-file-path debug-output-dir certname uuid %)]

    (log/warn (format "Writing catalog debugging info for %s to %s" certname debug-output-dir))
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
