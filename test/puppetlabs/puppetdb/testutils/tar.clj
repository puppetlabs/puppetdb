(ns puppetlabs.puppetdb.testutils.tar
  (:require [puppetlabs.puppetdb.archive :as archive]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.time :refer [now]]))

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
    {"timestamp" (now)
     "command_versions" {"replace_facts" 4
                         "replace_catalog" 6
                         "store_report" 5}}))

(defn tar-entry->map-path
  [tar-entry]
  (-> (.getName tar-entry) fs/split rest vec))

(defn tar->map
  "Convert elements in an import/export tarball to a hashmap.
   Nested directories convert to nested maps with the files
   converted from JSON to clojure data structures"
  [tar-file]
  (with-open [tar-reader (archive/tarball-reader tar-file)]
    (reduce (fn [acc tar-entry]
              (assoc-in acc
                        (tar-entry->map-path tar-entry)
                        (utils/read-json-content tar-reader)))
            {} (archive/all-entries tar-reader))))
