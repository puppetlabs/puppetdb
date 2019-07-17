(ns puppetlabs.puppetdb.command.constants
  (:require [clojure.set :as set]))

(def command-names
  {:configure-expiration "configure expiration"
   :replace-catalog "replace catalog"
   :replace-catalog-inputs "replace catalog inputs"
   :replace-facts   "replace facts"
   :deactivate-node "deactivate node"
   :store-report    "store report"})

(def command-keys (set/map-invert command-names))

(defn- version-range [min-version max-version]
  (set (range min-version (inc max-version))))

(def supported-command-versions
  {"configure expiration" (version-range 1 1)
   "replace facts" (version-range 2 5)
   "replace catalog" (version-range 4 9)
   "replace catalog inputs" (version-range 1 1)
   "store report" (version-range 3 8)
   "deactivate node" (version-range 1 3)})

(def latest-catalog-version (apply max (get supported-command-versions "replace catalog")))
(def latest-report-version (apply max (get supported-command-versions "store report")))
(def latest-facts-version (apply max (get supported-command-versions "replace facts")))
(def latest-configure-expiration-version (apply max (get supported-command-versions "configure expiration")))
(def latest-catalog-inputs-version (apply max (get supported-command-versions "replace catalog inputs")))

(def latest-command-versions
  {:replace_catalog latest-catalog-version
   :store_report latest-report-version
   :replace_facts latest-facts-version
   :configure_expiration latest-configure-expiration-version
   :replace-catalog-inputs latest-catalog-inputs-version})
