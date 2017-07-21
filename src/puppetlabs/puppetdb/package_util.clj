(ns puppetlabs.puppetdb.package-util
  (:require [schema.core :as s]))

;;; small utilities for manipulating package data; in a separate file to untangle
;;; a dependency loop.

(def package-tuple
  [(s/one s/Str "package_name")
   (s/one s/Str "version")
   (s/one s/Str "provider")])

(def hashed-package-tuple
  (conj package-tuple
        (s/one s/Str "package_hash")))

(defn package-tuple-hash [hashed-package-tuple]
  (nth hashed-package-tuple 3))
