(ns com.puppetlabs.puppetdb.testutils.tar
  (:require [com.puppetlabs.archive :as archive]
            [clojure.string :as str]
            [clj-time.core :as time]
            [com.puppetlabs.cheshire :as json]
            [fs.core :as fs]
            [com.puppetlabs.puppetdb.utils :refer [export-root-dir]]))

(defn path
  "Creates a platform independent relative path built
   from `path-segments`"
  [& path-segments]
  (str/join java.io.File/separator path-segments))

(defn assoc-metadata
  "Creates an export/import metadata map with the current
   time."
  [tar-map]
  (assoc tar-map
         (path "puppetdb-bak" "export-metadata.json")
         {"timestamp" (time/now)
          "command-versions" {"replace-facts" 1
                              "replace-catalog" 3
                              "store-report" 2}}))

(defn collapse-tar-map
  "Collapses the nested map structured created by mapify into
   relative paths, expected by add-entry. Also adds the puppetdb-bak
   root directory and extensions to all paths"
  [m]
  (reduce-kv (fn [top-level dir file-map]
               (merge top-level
                      (reduce-kv (fn [acc filename contents]
                                   (assoc acc (path export-root-dir dir (str filename ".json")) contents))
                                 {} file-map)))
             {} m))

(defn spit-tar
  "Given a `tar-map` created by something like mapify, create a new
   tarball representing that tar-map `f`"
  [f tar-map]
  (let [collapsed-map (assoc-metadata (collapse-tar-map tar-map))]
    (with-open [tar-writer (archive/tarball-writer f)]
      (doseq [[path clj-contents] collapsed-map]
        (archive/add-entry tar-writer "UTF-8" path (json/generate-pretty-string clj-contents))))))

(defn mapify
  "Convert elements in an import/export tarball to a hashmap.
   Nested directories convert to nested maps with the files
   converted from JSON to clojure data structures"
  [file]
  (with-open [tar (archive/tarball-reader file)]
    (reduce (fn [acc tar-entry]
              (assoc-in acc
                        (-> (.getName tar-entry)
                            fs/split
                            rest
                            vec)
                        (json/parse-string (archive/read-entry-content tar))))
            {} (archive/all-entries tar))))

