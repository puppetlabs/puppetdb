(ns puppetlabs.puppetdb.testutils.nio
  (:import [java.nio.file Path Files Paths]
           [java.nio.file.attribute FileAttribute]))

(defn ^Path path-get [^String s & more-strings]
  (Paths/get s (into-array String more-strings)))

(defn create-temp-dir [^Path path ^String prefix]
  (Files/createDirectories path (into-array FileAttribute []))
  (Files/createTempDirectory path prefix (into-array FileAttribute [])))

(defn resolve-path [^Path path ^String suffix]
  (.resolve path suffix))
