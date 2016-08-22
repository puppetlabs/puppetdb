(ns puppetlabs.puppetdb.testutils.nio
  (:import [java.nio.file Path Files]
           [java.nio.file.attribute FileAttribute]))

(defn create-temp-dir [^Path path ^String prefix]
  (Files/createDirectories path (into-array FileAttribute []))
  (Files/createTempDirectory path prefix (into-array FileAttribute [])))

(defn resolve-path [^Path path ^String suffix]
  (.resolve path suffix))
