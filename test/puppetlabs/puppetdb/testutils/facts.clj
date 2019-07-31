(ns puppetlabs.puppetdb.testutils.facts
  (:require [puppetlabs.puppetdb.testutils.tar :as tar]
            [puppetlabs.puppetdb.utils :as utils]))

(def base-facts
  "A minimal set of facts useful for testing"
  {"hardwaremodel" "x86_64"
   "memorysize" "16.00 GB"
   "memorytotal" "16.00 GB"
   "puppetversion" "3.4.2"
   "ipaddress_lo0" "127.0.0.1"
   "id" "foo"
   "operatingsystem" "Debian"})

(def base-package-inventory
  [["ntp" "4.2.8" "apt"]
   ["puppet-agent" "6.6.0" "apt"]
   ["nokogiri" "4.5.6" "gem"]])

(defn create-host-facts
  "Create a map for `node` suitable for spitting to a tarball
   used by import/export/anonymize"
  [node additional-facts]
  {"facts"
   {node
    {"certname" node
     "environment" "DEV"
     "values" (merge base-facts additional-facts)}}})

(defn munge-facts
  [facts]
  (->> facts
       utils/vector-maybe
       (map #(dissoc % :producer_timestamp :hash))))
