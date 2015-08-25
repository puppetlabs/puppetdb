(ns puppetlabs.puppetdb.query.report-data
  (:require [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(pls/defn-validated munge-result-rows
  "Intended to be used for parsing the results
  from either the report :metrics or :logs queries."
  [data :- s/Keyword]
  (fn [_ _]
   (fn [rows]
     (if-let [maybe-json (-> rows first data)]
       (sutils/parse-db-json maybe-json)
       []))))
