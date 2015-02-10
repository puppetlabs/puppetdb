(ns puppetlabs.puppetdb.testutils.resources
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.scf.storage :refer [add-facts! ensure-environment]]
            [puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize to-jdbc-varchar-array]]))

(defn store-example-resources
  ([] (store-example-resources true))
  ([environment?]
     (with-transacted-connection *db*
       (sql/insert-records
        :resource_params_cache
        {:resource "1" :parameters (db-serialize {"ensure" "file"
                                                  "owner"  "root"
                                                  "group"  "root"
                                                  "acl"    ["john:rwx" "fred:rwx"]})}
        {:resource "2" :parameters nil})
       (sql/insert-records
        :resource_params
        {:resource "1" :name "ensure" :value (db-serialize "file")}
        {:resource "1" :name "owner"  :value (db-serialize "root")}
        {:resource "1" :name "group"  :value (db-serialize "root")}
        {:resource "1" :name "acl"    :value (db-serialize ["john:rwx" "fred:rwx"])})
       (sql/insert-records
        :certnames
        {:name "one.local"}
        {:name "two.local"})
       (sql/insert-records
        :catalogs
        {:id 1 :hash "foo" :api_version 1 :catalog_version "12" :certname "one.local" :environment_id (when environment? (ensure-environment "DEV"))}
        {:id 2 :hash "bar" :api_version 1 :catalog_version "14" :certname "two.local" :environment_id (when environment? (ensure-environment "PROD"))})
       (add-facts! {:name "one.local"
                    :values {"operatingsystem" "Debian"
                             "kernel" "Linux"
                             "uptime_seconds" 50000}
                    :timestamp (now)
                    :environment "DEV"
                    :producer_timestamp nil})
       (add-facts! {:name "two.local"
                    :values {"operatingsystem" "Ubuntu"
                             "kernel" "Linux"
                             "uptime_seconds" 10000
                             "message" "hello"}
                    :timestamp (now)
                    :environment "DEV"
                    :producer_timestamp nil})
       (sql/insert-records :catalog_resources
                           {:catalog_id 1 :resource "1" :type "File" :title "/etc/passwd" :exported false :tags (to-jdbc-varchar-array ["one" "two"])}
                           {:catalog_id 1 :resource "2" :type "Notify" :title "hello" :exported false :tags (to-jdbc-varchar-array [])}
                           {:catalog_id 2 :resource "1" :type "File" :title "/etc/passwd" :exported false :tags (to-jdbc-varchar-array ["one" "two"])}
                           {:catalog_id 2 :resource "2" :type "Notify" :title "hello" :exported true :file "/foo/bar" :line 22 :tags (to-jdbc-varchar-array [])}))

     {:foo1 {:certname   "one.local"
             :resource   "1"
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
             :resource   "2"
             :type       "Notify"
             :title      "hello"
             :tags       []
             :exported   false
             :file nil
             :line nil
             :environment (when environment? "DEV")
             :parameters {}}
      :bar1 {:certname   "two.local"
             :resource   "1"
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
             :resource   "2"
             :type       "Notify"
             :title      "hello"
             :tags       []
             :exported   true
             :file "/foo/bar"
             :line 22
             :environment (when environment? "PROD")
             :parameters {}}}))
