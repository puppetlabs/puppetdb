(ns puppetlabs.puppetdb.testutils.nio
 (:require
  [me.raynes.fs :refer [delete-dir]]
  [puppetlabs.puppetdb.lint :refer [ignore-value]])
 (:import
  [java.nio.file Path Files]
  [java.nio.file.attribute FileAttribute]))

(defn create-temp-dir [^Path path ^String prefix]
  (ignore-value (Files/createDirectories path (into-array FileAttribute [])))
  (Files/createTempDirectory path prefix (into-array FileAttribute [])))

(defn resolve-path [^Path path ^String suffix]
  (.resolve path suffix))

(defn call-with-temp-dir-path
  "Calls (f temp-dir-path) after creating the temporary directory
  inside the parent path, and then deletes the directory if f doesn't
  throw an Exception.  Prepends the prefix, if not nil, to the
  temporary directory's name."
  [parent prefix f]
  (let [tempdir (Files/createTempDirectory parent prefix
                                           (make-array FileAttribute 0))
        tempdirstr (str (.toAbsolutePath tempdir))
        result (try
                 (f (.toAbsolutePath tempdir))
                 (catch Exception ex
                   (binding [*out* *err*]
                     (println "Error: leaving temp dir" tempdirstr))
                   (throw ex)))]
    (delete-dir tempdirstr)
    result))
