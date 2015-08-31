(ns puppetlabs.puppetdb.testutils.cli
  (:require [puppetlabs.puppetdb.export :as export]))

(defn get-catalog [query-fn certname]
  (->> ["=" "certname" certname]
       (export/get-wireformatted-entity query-fn :catalogs)
       first))

(defn get-report [query-fn certname]
  (->> ["=" "certname" certname]
       (export/get-wireformatted-entity query-fn :reports)
       first))

(defn get-facts [query-fn certname]
  (->> ["=" "certname" certname]
       (export/get-wireformatted-entity query-fn :factsets)
       first))
