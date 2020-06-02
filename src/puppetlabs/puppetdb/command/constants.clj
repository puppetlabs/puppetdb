(ns puppetlabs.puppetdb.command.constants
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def command-names
  {:configure-expiration "configure expiration"
   :replace-catalog "replace catalog"
   :replace-catalog-inputs "replace catalog inputs"
   :replace-facts   "replace facts"
   :deactivate-node "deactivate node"
   :store-report    "store report"})

(def command-keys (set/map-invert command-names))

(def supported-command-versions
  {"configure expiration" #{1}
   "replace facts" #{2 3 4 5}
   "replace catalog" #{4 5 6 7 8 9}
   "replace catalog inputs" #{1}
   "store report" #{3 4 5 6 7 8}
   "deactivate node" #{1 2 3}})

(def latest-catalog-version (apply max (supported-command-versions "replace catalog")))
(def latest-report-version (apply max (supported-command-versions "store report")))
(def latest-facts-version (apply max (supported-command-versions "replace facts")))
(def latest-configure-expiration-version (apply max (supported-command-versions "configure expiration")))
(def latest-catalog-inputs-version (apply max (supported-command-versions "replace catalog inputs")))
(def latest-deactivate-node-version (apply max (supported-command-versions "deactivate node")))

(def latest-command-versions
  {:replace_catalog latest-catalog-version
   :store_report latest-report-version
   :replace_facts latest-facts-version
   :configure_expiration latest-configure-expiration-version
   :replace_catalog_inputs latest-catalog-inputs-version})

(defn normalize-command-name [command]
  "Normalize command name from an incoming request's query param"
  (str/replace command "_" " "))
