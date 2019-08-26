(ns puppetlabs.puppetdb.query.catalog-inputs
  (:require [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

(def row-schema
  (query/wrap-with-supported-fns
   {(s/optional-key :certname) s/Str
    (s/optional-key :producer_timestamp) pls/Timestamp
    (s/optional-key :catalog_uuid) s/Str
    (s/optional-key :inputs) [[s/Str]]}))

(def converted-row-schema
  (query/wrap-with-supported-fns
   {(s/optional-key :certname) s/Str
     (s/optional-key :producer_timestamp) pls/Timestamp
     (s/optional-key :catalog_uuid) s/Str
     (s/optional-key :inputs) [[s/Str]]}))

(pls/defn-validated convert-array-to-vec :- converted-row-schema
  [row]
  (utils/update-when row [:inputs] (partial map vec)))

(defn munge-catalog-inputs
  [_ _]
  (fn [rows]
    (map convert-array-to-vec rows)))
