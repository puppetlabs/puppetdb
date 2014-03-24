(ns com.puppetlabs.puppetdb.testutils.resources
  (:require [clojure.java.jdbc :as sql])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.scf.storage :only [add-facts!]]
        [com.puppetlabs.puppetdb.scf.storage-utils :only [db-serialize to-jdbc-varchar-array]]))

(defn store-example-resources
  []
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
      {:id 1 :hash "foo" :api_version 1 :catalog_version "12" :certname "one.local"}
      {:id 2 :hash "bar" :api_version 1 :catalog_version "14" :certname "two.local"})
    (add-facts! "one.local"
      {"operatingsystem" "Debian"
       "kernel" "Linux"
       "uptime_seconds" 50000}
      (now)
      "DEV")
    (add-facts! "two.local"
      {"operatingsystem" "Ubuntu"
       "kernel" "Linux"
       "uptime_seconds" 10000
       "message" "hello"}
      (now)
      "DEV")
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
          :parameters {}}
   :bar1 {:certname   "two.local"
          :resource   "1"
          :type       "File"
          :title      "/etc/passwd"
          :tags       ["one" "two"]
          :exported   false
          :file nil
          :line nil
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
          :parameters {}}})
