(ns puppetlabs.puppetdb.query.edges
  "Fact query generation"
  (:require [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

;; SCHEMA

(def edge-schema
  {(s/optional-key :certname) String
   (s/optional-key :relationship) String
   (s/optional-key :source_title) String
   (s/optional-key :source_type) String
   (s/optional-key :target_title) String
   (s/optional-key :target_type) String})

;; MUNGE

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [_ _]
  (fn [rows]
    (map #(s/validate edge-schema %) rows)))
