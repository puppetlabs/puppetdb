(ns puppetlabs.puppetdb.nodes
  "Puppet nodes parsing

   Functions that handle conversion of nodes from wire format to
   internal PuppetDB format, including validation."
  (:require
   [schema.core :as s]
   [puppetlabs.puppetdb.schema :as pls]))

(def deactivate-node-wireformat-schema
  {:certname s/Str
   (s/optional-key :producer_timestamp) (s/maybe pls/Timestamp)})
