(ns com.puppetlabs.puppetdb.testutils.resources
  (:require [clojure.java.jdbc :as sql])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize to-jdbc-varchar-array add-facts!]]))

(defn store-example-resources
  []
  (with-transacted-connection *db*
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
      {:hash "foo" :api_version 1 :catalog_version "12"}
      {:hash "bar" :api_version 1 :catalog_version "14"})
    (sql/insert-records
      :certname_catalogs
      {:certname "one.local" :catalog "foo"}
      {:certname "two.local" :catalog "bar"})
    (add-facts! "one.local"
      {"operatingsystem" "Debian"
       "kernel" "Linux"
       "uptime_seconds" 50000}
      (now))
    (add-facts! "two.local"
      {"operatingsystem" "Ubuntu"
       "kernel" "Linux"
       "uptime_seconds" 10000
       "message" "hello"}
      (now))
    (sql/insert-records :catalog_resources
      {:catalog "foo" :resource "1" :type "File" :title "/etc/passwd" :exported false :tags (to-jdbc-varchar-array ["one" "two"])}
      {:catalog "foo" :resource "2" :type "Notify" :title "hello" :exported false :tags (to-jdbc-varchar-array [])}
      {:catalog "bar" :resource "1" :type "File" :title "/etc/passwd" :exported false :tags (to-jdbc-varchar-array ["one" "two"])}
      {:catalog "bar" :resource "2" :type "Notify" :title "hello" :exported true :tags (to-jdbc-varchar-array [])}))

  {:foo1 {:certname   "one.local"
          :resource   "1"
          :type       "File"
          :title      "/etc/passwd"
          :tags       ["one" "two"]
          :exported   false
          :sourcefile nil
          :sourceline nil
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
          :sourcefile nil
          :sourceline nil
          :parameters {}}
   :bar1 {:certname   "two.local"
          :resource   "1"
          :type       "File"
          :title      "/etc/passwd"
          :tags       ["one" "two"]
          :exported   false
          :sourcefile nil
          :sourceline nil
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
          :sourcefile nil
          :sourceline nil
          :parameters {}}})