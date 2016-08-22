(ns puppetlabs.puppetdb.nio
  (:import
   [java.nio.file Path Files Paths]))

(defn ^Path get-path [^String s & more-strings]
  (Paths/get s (into-array String more-strings)))
