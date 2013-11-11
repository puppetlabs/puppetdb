(ns com.puppetlabs.puppetdb.scf.hash-debug
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.catalogs :as qcat]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [com.puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [com.puppetlabs.utils :as utils]
            [fs.core :as fs]))

(defn debug-file-path
  "Creates a unique path name for the `certname`. Uses a UUID to ensure uniqueness."
  [debug-output-dir certname file-name]
  (fs/file debug-output-dir (format "%s_%s_%s" certname (utils/uuid) file-name)))

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

(defn debug-catalog [debug-output-dir new-hash {certname :certname new-resources :resources new-edges :edges :as catalog}]
  (let [{old-resources :resources old-edges :edges :as foo} (:data (qcat/catalog-for-node certname))
        old-catalog (shash/catalog-similarity-format certname old-resources old-edges)
        new-catalog (shash/catalog-similarity-format certname (vals new-resources) new-edges)
        file-path-fn #(debug-file-path debug-output-dir certname %)]

    (log/warn (format "Writing catalog debugging info for %s to %s" certname debug-output-dir))
    (json/spit-json (file-path-fn "catalog-metadata.json")
                    (-> {"new catalog hash" new-hash
                         "old catalog hash" (-> old-catalog json/generate-string utils/utf8-string->sha1)
                         "java version" utils/java-version
                         "database name" (sutils/sql-current-connection-database-name)
                         "database version" (sutils/sql-current-connection-database-version)}
                        (output-clj-catalog "old catalog path - edn" (file-path-fn "old-catalog.edn") old-catalog)
                        (output-clj-catalog "new catalog path - edn" (file-path-fn "new-catalog.edn") new-catalog)
                        (output-json-catalog "old catalog path - json" (file-path-fn "old-catalog.json") old-catalog)
                        (output-json-catalog "new catalog path - json" (file-path-fn "new-catalog.json") new-catalog)))))
