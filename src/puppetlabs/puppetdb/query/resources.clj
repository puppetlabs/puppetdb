(ns puppetlabs.puppetdb.query.resources
  "Resource querying

   This implements resource querying, using the query compiler in
   `puppetlabs.puppetdb.query`, basically by munging the results into the
   right format and picking out the desired columns."
  (:import [org.postgresql.util PGobject])
  (:require [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.spec.core :as spec]
            [schema.spec.leaf :as leaf]
            [schema.core :as s]))

;; SCHEMA

(defrecord OptionalNameMatching [pattern]
  s/Schema
  (spec [this] (leaf/leaf-spec
                 (spec/precondition this
                                    #(re-matches pattern (name %))
                                    #(list 're-matches pattern %))))
  (explain [this] '(re-matches pattern)))

(defn optional-matching-keyword
  "A regex pattern to check the keyword for."
  [pattern]
  (->OptionalNameMatching pattern))

(def row-schema
  "Resource query row schema."
  (query/wrap-with-supported-fns
    {(s/optional-key :certname) s/Str
     (s/optional-key :environment) (s/maybe s/Str)
     (s/optional-key :exported) s/Bool
     (s/optional-key :file) (s/maybe s/Str)
     (s/optional-key :line) (s/maybe s/Int)
     (s/optional-key :parameters) (s/maybe PGobject)
     (optional-matching-keyword #"parameters\..*") (s/maybe PGobject)
     (s/optional-key :resource) s/Str
     (s/optional-key :tags) [(s/maybe s/Str)]
     (s/optional-key :title) s/Str
     (s/optional-key :type) s/Str}))

;; MUNGE

(pls/defn-validated row->resource
  "Convert resource query row into a final resource format."
  [row :- row-schema]
  (utils/update-when row [:parameters] #(or % {})))

(pls/defn-validated munge-result-rows
  "Munge the result rows so that they will be compatible with the version
  specified API specification"
  [_ _]
  (fn [rows]
    (map row->resource rows)))
