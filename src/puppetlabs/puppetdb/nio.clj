(ns puppetlabs.puppetdb.nio
  (:import
   [java.nio.file CopyOption Path OpenOption Paths StandardCopyOption]))

(def copt-atomic StandardCopyOption/ATOMIC_MOVE)
(def copt-replace StandardCopyOption/REPLACE_EXISTING)
(def copts-type (class (into-array CopyOption [])))

(defn ^copts-type copts [opts]
  (into-array CopyOption opts))

(def oopts-type (class (into-array OpenOption [])))

(defn ^oopts-type oopts [opts]
  (into-array OpenOption opts))

(defn ^Path get-path [^String s & more-strings]
  (Paths/get s (into-array String more-strings)))
