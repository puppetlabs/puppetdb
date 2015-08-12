(ns puppetlabs.puppetdb.query.catalogs
  "Catalog retrieval"
  (:require [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; MUNGE

(pls/defn-validated row->catalog
  "Return a function that will convert a catalog query row into a final catalog format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:edges] utils/child->expansion :catalogs :edges base-url)
        (utils/update-when [:resources] utils/child->expansion :catalogs :resources base-url))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (utils/as-path url-prefix (name version))]
    (fn [rows]
      (map (row->catalog base-url) rows))))
