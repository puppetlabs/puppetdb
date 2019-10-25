(ns puppetlabs.puppetdb.nodes
  "Puppet nodes parsing

   Functions that handle conversion of nodes from wire format to
   internal PuppetDB format, including validation."
  (:require
   [schema.core :as s]
   [clojure.set :as set]
   [puppetlabs.puppetdb.schema :as pls]
   [puppetlabs.puppetdb.utils :as utils]
   [com.rpl.specter :as sp]))

;; SCHEMA

(def expire-wireformat-schema
  {:facts s/Bool})

(def configure-expiration-wireformat-schema
  {:certname s/Str
   :expire expire-wireformat-schema
   (s/optional-key :producer_timestamp) (s/maybe pls/Timestamp)})

(def nodes-wireformat-schema
  {:certname s/Str
   :deactivated (s/maybe s/Str)
   :expired (s/maybe pls/Timestamp)
   :catalog_timestamp (s/maybe pls/Timestamp)
   :facts_timestamp (s/maybe pls/Timestamp)
   :report_timestamp (s/maybe pls/Timestamp)
   :catalog_environment (s/maybe s/Str)
   :facts_environment (s/maybe s/Str)
   :report_environment (s/maybe s/Str)
   :latest_report_status (s/maybe s/Str)
   :latest_report_hash (s/maybe s/Str)
   :latest_report_noop (s/maybe s/Bool)
   :latest_report_noop_pending (s/maybe s/Bool)
   :cached_catalog_status (s/maybe s/Str)
   :latest_report_corrective_change (s/maybe s/Bool)
   :latest_report_job_id (s/maybe s/Str)
   :expires_facts (s/maybe s/Bool)
   :expires_facts_updated (s/maybe pls/Timestamp)})

(pls/defn-validated nodes-query->configure-expiration-wire-v1
  :- [configure-expiration-wireformat-schema]
  [nodes :- [nodes-wireformat-schema]]
  (->> nodes
       (filter :expires_facts_updated)
       (map (fn [x]
              {:certname (:certname x)
               :expire {:facts (:expires_facts x)}
               :producer_timestamp (:expires_facts_updated x)}))))

(def deactivate-node-wireformat-schema
  {:certname s/Str
   (s/optional-key :producer_timestamp) (s/maybe pls/Timestamp)})
