;; Wrapper for marginalia that passes in the correct name,
;; description, and version to the marginalia parser.

(ns leiningen.docs
  (:require [leiningen.marg :as marg]))

(defn docs
  [project]
  (marg/marg project "-n" (:name project) "-D" (:description project) "-v" (:version project)))
