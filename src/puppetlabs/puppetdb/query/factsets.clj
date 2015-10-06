(ns puppetlabs.puppetdb.query.factsets
  (:require [clojure.set :as set]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; MUNGE

(pls/defn-validated row->factset
  "Convert factset query row into a final factset format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:facts] utils/child->expansion :factsets :facts base-url)
        (utils/update-when [:facts :data] (partial map #(update % :value json/parse-string))))))

(pls/defn-validated munge-result-rows
  "Reassemble rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (utils/as-path url-prefix (name version))]
    (partial map (row->factset base-url))))
