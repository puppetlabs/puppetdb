(ns puppetlabs.puppetdb.factsets
  (:require [clojure.edn :as clj-edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]
            [puppetlabs.puppetdb.scf.storage :as scf-store]))

;; SCHEMA

(def fact-query-schema
  "Schema for a single fact map"
  {:name s/Str
   :value s/Any
   (s/optional-key :environment) s/Str
   (s/optional-key :certname) s/Str})

(def facts-expanded-query-schema
  "Facts expanded data format."
  {(s/optional-key :data) [fact-query-schema]
   :href String})

(def factset-query-schema
  "Final schema for a single factset."
  {(s/optional-key :certname) String
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :timestamp) pls/Timestamp
   (s/optional-key :producer_timestamp) (s/maybe pls/Timestamp)
   (s/optional-key :hash) (s/maybe s/Str)
   (s/optional-key :facts) facts-expanded-query-schema})

(def facts-wireformat-schema
  {:certname s/Str
   :values {s/Keyword s/Any}
   :environment (s/maybe s/Str)
   :producer_timestamp (s/cond-pre pls/Timestamp (s/maybe s/Str))})

;; TRANSFORMATIONS

(pls/defn-validated fact-query->wire-v5
  [fact :- fact-query-schema]
  (-> fact
      (dissoc :environment :certname)))

(pls/defn-validated facts-list-to-map :- {s/Keyword s/Any}
  [facts :- [fact-query-schema]]
  (zipmap (map (comp keyword :name) facts)
          (map :value facts)))

(pls/defn-validated facts-expanded->wire-v5-classic :- {s/Keyword s/Any}
  [facts :- facts-expanded-query-schema]
  (facts-list-to-map
   (map fact-query->wire-v5 (:data facts))))

(defn facts-expanded->wire-v5 [facts]
  (facts-expanded->wire-v5-classic facts))

(defn factsets-query->wire-v5 [factsets]
  (map
   #(-> %
        (dissoc :hash :timestamp)
        (update :facts facts-expanded->wire-v5)
        (set/rename-keys {:facts :values}))
   factsets))
