(ns puppetlabs.puppetdb.testutils.resources
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage
             :refer [add-facts! ensure-environment]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [to-jdbc-varchar-array]]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]))

(defn store-example-resources
  ([] (store-example-resources true))
  ([environment?]
   (jdbc/with-transacted-connection *db*
     (jdbc/insert!
       :resource_params_cache
       {:resource (sutils/munge-hash-for-storage "01")
        :parameters (sutils/munge-jsonb-for-storage
                      {"ensure" "file"
                       "owner"  "root"
                       "group"  "root"
                       "acl"    ["john:rwx" "fred:rwx"]})}
       {:resource (sutils/munge-hash-for-storage "02")
        :parameters nil})
     (jdbc/insert!
       :resource_params
       {:resource (sutils/munge-hash-for-storage "01") :name "ensure"
        :value (sutils/db-serialize "file")}
       {:resource (sutils/munge-hash-for-storage "01") :name "owner"
        :value (sutils/db-serialize "root")}
       {:resource (sutils/munge-hash-for-storage "01") :name "group"
        :value (sutils/db-serialize "root")}
       {:resource (sutils/munge-hash-for-storage "01") :name "acl"
        :value (sutils/db-serialize ["john:rwx" "fred:rwx"])})
       (jdbc/insert!
        :certnames
        {:id 1 :certname "one.local"}
        {:id 2 :certname "two.local"})
       (jdbc/insert!
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
                    :producer_timestamp (to-timestamp (now))
                    :producer "foo.com"})
       (add-facts! {:certname "two.local"
                    :values {"operatingsystem" "Ubuntu"
                             "kernel" "Linux"
                             "uptime_seconds" 10000
                             "message" "hello"}
                    :timestamp (to-timestamp (now))
                    :environment "DEV"
                    :producer_timestamp (to-timestamp (now))
                    :producer "foo.com"})

       (jdbc/insert! :latest_catalogs {:catalog_id 1 :certname_id 1})
       (jdbc/insert! :latest_catalogs {:catalog_id 2 :certname_id 2})
       (jdbc/insert!
        :catalog_resources
        {:certname_id 1 :resource (sutils/munge-hash-for-storage "01")
         :type "File" :title "/etc/passwd" :exported false
         :tags (to-jdbc-varchar-array ["one" "two"])}
        {:certname_id 1 :resource (sutils/munge-hash-for-storage "02")
         :type "Notify" :title "hello" :exported false
         :tags (to-jdbc-varchar-array [])}
        {:certname_id 2 :resource (sutils/munge-hash-for-storage "01")
         :type "File" :title "/etc/passwd" :exported false
         :tags (to-jdbc-varchar-array ["one" "two"])}
        {:certname_id 2 :resource (sutils/munge-hash-for-storage "02")
         :type "Notify" :title "hello" :exported true :file "/foo/bar" :line 22
         :tags (to-jdbc-varchar-array [])}))

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
