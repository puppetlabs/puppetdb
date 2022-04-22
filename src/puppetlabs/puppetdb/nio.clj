(ns puppetlabs.puppetdb.nio
  (:import
   [java.nio.file CopyOption Path OpenOption Paths StandardCopyOption]))

(def copt-atomic StandardCopyOption/ATOMIC_MOVE)
(def copt-replace StandardCopyOption/REPLACE_EXISTING)

(defn copts ^"[Ljava.nio.file.CopyOption;" [opts]
  (into-array CopyOption opts))

(defn oopts ^"[Ljava.nio.file.OpenOption;" [opts]
  (into-array OpenOption opts))

(defn get-path ^Path [^String s & more-strings]
  (Paths/get s (into-array String more-strings)))
