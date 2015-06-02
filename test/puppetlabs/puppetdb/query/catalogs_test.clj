(ns puppetlabs.puppetdb.query.catalogs-test
  (:require [clj-time.coerce :as time-coerce]
            [cheshire.core :as json]
            [clojure.java.io :refer [resource]]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [puppetlabs.puppetdb.utils :as utils]))

(use-fixtures :each with-test-db)

;; TRANSFORMATIONS

(defn strip-expanded
  "Strips out expanded data from the wire format if the database is HSQLDB"
  [catalog]
  (if (sutils/postgres?)
    catalog
    (dissoc catalog :edges :resources)))

(defn munge-resources
  [resources]
  (map (comp #(update % :tags vec)
             #(update % :parameters keywordize-keys))
   (set resources)))

(defn munge-expected
  [catalog]
  (-> (testcat/munged-canonical->wire-format :v5 catalog)
      keywordize-keys
      strip-expanded
      (utils/update-when [:resources] munge-resources)
      (utils/update-when [:edges] vec)))

(defn munge-actual
  [catalog]
  (-> catalog
      catalogs/catalog-query->wire-v6
      (update :producer_timestamp time-coerce/to-string)
      strip-expanded
      (utils/update-when [:resources] munge-resources)))
