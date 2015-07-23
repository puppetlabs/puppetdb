(ns puppetlabs.puppetdb.testutils.resources
  (:require [clojure.java.jdbc.deprecated :as sql]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.scf.storage :refer [add-facts! ensure-environment]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils :refer [db-serialize to-jdbc-varchar-array]]))

(defn store-example-resources
  ([] (store-example-resources true))
  ([environment?]
     (with-transacted-connection *db*
       (sql/insert-records
        :resource_params_cache
        {:resource (sutils/munge-hash-for-storage "01")
         :parameters (db-serialize {"ensure" "file"
                                    "owner"  "root"
                                    "group"  "root"
                                    "acl"    ["john:rwx" "fred:rwx"]})}
        {:resource (sutils/munge-hash-for-storage "02")
         :parameters nil})
       (sql/insert-records
        :resource_params
        {:resource (sutils/munge-hash-for-storage "01") :name "ensure" :value (db-serialize "file")}
        {:resource (sutils/munge-hash-for-storage "01") :name "owner"  :value (db-serialize "root")}
        {:resource (sutils/munge-hash-for-storage "01") :name "group"  :value (db-serialize "root")}
        {:resource (sutils/munge-hash-for-storage "01") :name "acl"    :value (db-serialize ["john:rwx" "fred:rwx"])})
       (sql/insert-records
        :certnames
        {:certname "one.local"}
        {:certname "two.local"})
       (sql/insert-records
        :catalogs
        {:id 1
         :hash (sutils/munge-hash-for-storage "f0")
         :api_version 1
         :catalog_version "12"
         :certname "one.local"
         :producer_timestamp (to-timestamp (now))
         :environment_id (when environment? (ensure-environment "DEV"))}
        {:id 2
         :hash (sutils/munge-hash-for-storage "ba")
         :api_version 1
         :catalog_version "14"
         :certname "two.local"
         :producer_timestamp (to-timestamp (now))
         :environment_id (when environment? (ensure-environment "PROD"))})
       (add-facts! {:certname "one.local"
                    :values {"operatingsystem" "Debian"
                             "kernel" "Linux"
                             "uptime_seconds" 50000}
                    :timestamp (to-timestamp (to-timestamp (now)))
                    :environment "DEV"
                    :producer_timestamp (to-timestamp (now))})
       (add-facts! {:certname "two.local"
                    :values {"operatingsystem" "Ubuntu"
                             "kernel" "Linux"
                             "uptime_seconds" 10000
                             "message" "hello"}
                    :timestamp (to-timestamp (now))
                    :environment "DEV"
                    :producer_timestamp (to-timestamp (now))})
       (sql/insert-records :catalog_resources
                           {:catalog_id 1 :resource (sutils/munge-hash-for-storage "01") :type "File" :title "/etc/passwd" :exported false :tags (to-jdbc-varchar-array ["one" "two"])}
                           {:catalog_id 1 :resource (sutils/munge-hash-for-storage "02") :type "Notify" :title "hello" :exported false :tags (to-jdbc-varchar-array [])}
                           {:catalog_id 2 :resource (sutils/munge-hash-for-storage "01") :type "File" :title "/etc/passwd" :exported false :tags (to-jdbc-varchar-array ["one" "two"])}
                           {:catalog_id 2 :resource (sutils/munge-hash-for-storage "02") :type "Notify" :title "hello" :exported true :file "/foo/bar" :line 22 :tags (to-jdbc-varchar-array [])}))

     {:foo1 {:certname   "one.local"
             :resource   "01"
             :type       "File"
             :title      "/etc/passwd"
             :tags       ["one" "two"]
             :exported   false
             :file nil
             :line nil
             :environment (when environment? "DEV")
             :parameters {:ensure "file"
                          :owner  "root"
                          :group  "root"
                          :acl    ["john:rwx" "fred:rwx"]}}
      :foo2 {:certname   "one.local"
             :resource   "02"
             :type       "Notify"
             :title      "hello"
             :tags       []
             :exported   false
             :file nil
             :line nil
             :environment (when environment? "DEV")
             :parameters {}}
      :bar1 {:certname   "two.local"
             :resource   "01"
             :type       "File"
             :title      "/etc/passwd"
             :tags       ["one" "two"]
             :exported   false
             :file nil
             :line nil
             :environment (when environment? "PROD")
             :parameters {:ensure "file"
                          :owner  "root"
                          :group  "root"
                          :acl    ["john:rwx" "fred:rwx"]}}
      :bar2 {:certname   "two.local"
             :resource   "02"
             :type       "Notify"
             :title      "hello"
             :tags       []
             :exported   true
             :file "/foo/bar"
             :line 22
             :environment (when environment? "PROD")
             :parameters {}}}))
