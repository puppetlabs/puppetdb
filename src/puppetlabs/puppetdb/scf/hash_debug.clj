(ns puppetlabs.puppetdb.scf.hash-debug
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng :as eng]
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
  (map #(update % :relationship keyword) edges))

(defn resource-params->kwds
  "Updates the resource parameter keys from the DB to keywords for easier diffing
   with the new catalog."
  [resources]
  (map #(update % :parameters (partial kitchensink/mapkeys keyword)) resources))

(defn get-entity
  "An unfortunate copy of `query_eng.clj`'s `stream-query-result` without the `with-transacted-connection`
   because we use the old version of jdbc which means that we don't pass the db-spec down through
   this codepath and need to rely on the dynamic db connection."
  [entity version query]
  (let [munge-fn ((get-in @eng/entity-fn-idx [entity :munge]) version "")
        {:keys [results-query]} (eng/query->sql query entity version {})]
    (jdbc/with-query-results-cursor results-query (comp doall munge-fn))))

(defn maybe-expand
  [parent child version query]
  (if (contains? (get parent child) :data)
    parent
    (assoc-in parent [child :data] (get-entity child version query))))

(defn diffable-old-catalog
  "Query for the existing catalog from the DB, prep the results
   for easy diffing."
  [certname]
  (let [version :v4
        query ["=" "certname" certname]]
    (-> (get-entity :catalogs version query)
        first
        (maybe-expand :resources version query)
        (maybe-expand :edges version query)
        catalogs/catalog-query->wire-v6
        (update :edges edge-relationships->kwds)
        (update :resources resource-params->kwds))))

(defn debug-catalog
  [debug-output-dir new-hash {certname :certname new-resources :resources new-edges :edges :as catalog}]
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
                         "database name" (:database @sutils/db-metadata)
                         "database version" (:version @sutils/db-metadata)}
                        (output-clj-catalog "old catalog path - edn" (file-path-fn "catalog-old.edn") old-catalog)
                        (output-clj-catalog "new catalog path - edn" (file-path-fn "catalog-new.edn") new-catalog)
                        (output-json-catalog "old catalog path - json" (file-path-fn "catalog-old.json") old-catalog)
                        (output-json-catalog "new catalog path - json" (file-path-fn "catalog-new.json") new-catalog)))))
