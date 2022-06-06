(ns puppetlabs.puppetdb.query.fact-contents
  (:require [puppetlabs.puppetdb.facts :as f]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

(def row-schema
  (query/wrap-with-supported-fns
   {(s/optional-key :certname) s/Str
    (s/optional-key :environment) (s/maybe s/Str)
    (s/optional-key :path) [s/Str]
    (s/optional-key :path_types) s/Str
    (s/optional-key :name) s/Str
    (s/optional-key :value) (s/maybe s/Any)}))

(def converted-row-schema
  (query/wrap-with-supported-fns
    {(s/optional-key :certname) s/Str
     (s/optional-key :environment) (s/maybe s/Str)
     (s/optional-key :path) f/fact-path
     (s/optional-key :name) s/Str
     (s/optional-key :value) s/Any}))

(defn adjust-path-types
  "Adjusts the types in the :path based on the signature provided by
  path_types to ensure that array indexes are returned as integers
  rather than strings."
  [{:keys [path path_types] :as row}]
  (if-not path
    (do
      (assert (not path_types))
      row)
    (do
      (assert path_types)
      (assert (= (count path) (count path_types)))
      (-> row
          (dissoc :path_types)
          (assoc :path (mapv (fn [^String part sig]
                               (case sig
                                 \s part
                                 \i (Long/valueOf part)))
                             path path_types))))))

(pls/defn-validated munge-result-row :- converted-row-schema
  "Coerce the value of a row to the proper type, and convert the path back to
   an array structure."
  [row :- row-schema]
  (-> (adjust-path-types row)
      (utils/update-when [:value] sutils/parse-db-json)))

(pls/defn-validated munge-result-rows
  "Munge resulting rows for fact-contents endpoint."
  [_ _]
  (fn [rows]
    (map munge-result-row rows)))
