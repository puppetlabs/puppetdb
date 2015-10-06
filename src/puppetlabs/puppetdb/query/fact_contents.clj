(ns puppetlabs.puppetdb.query.fact-contents
  (:require [puppetlabs.puppetdb.facts :as f]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

(def row-schema
  {(s/optional-key :certname) s/Str
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :path) s/Str
   (s/optional-key :name) s/Str
   (s/optional-key :value) (s/maybe s/Str)})

(def converted-row-schema
  {(s/optional-key :certname) s/Str
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :path) f/fact-path
   (s/optional-key :name) s/Str
   (s/optional-key :value) s/Any})

(pls/defn-validated munge-result-row :- converted-row-schema
  "Coerce the value of a row to the proper type, and convert the path back to
   an array structure."
  [row :- row-schema]
  (-> row
      (utils/update-when [:value] json/parse-string)
      (utils/update-when [:path] f/string-to-factpath)))

(pls/defn-validated munge-result-rows
  "Munge resulting rows for fact-contents endpoint."
  [_ _]
  (fn [rows]
    (map munge-result-row rows)))
