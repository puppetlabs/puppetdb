(ns puppetlabs.puppetdb.testutils.facts
  (:require [puppetlabs.puppetdb.testutils.tar :as tar]))

(def base-facts
  "A minimal set of facts useful for testing"
  {"hardwaremodel" "x86_64"
   "memorysize" "16.00 GB"
   "memorytotal" "16.00 GB"
   "puppetversion" "3.4.2"
   "ipaddress_lo0" "127.0.0.1"
   "id" "foo"
   "operatingsystem" "Debian"})

(defn create-host-facts
  "Create a map for `node` suitable for spitting to a tarball
   used by import/export/anonymize"
  [node additional-facts]
  {"facts"
   {node
    {"certname" node
     "environment" "DEV"
     "values" (merge base-facts additional-facts)}}})

(defn spit-facts-tarball
  "Merges fact-maps, then spits the file to disk at `f`"
  [f & fact-maps]
  (tar/spit-tar f (apply merge-with merge fact-maps)))

(defn munge-facts
  "Munge facts appropriately for comparison"
  [facts]
  (-> facts
      clojure.walk/stringify-keys
      (dissoc "producer_timestamp")))
