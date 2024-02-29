(ns puppetlabs.puppetdb.testutils.resources
  (:require [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage
             :refer [add-certnames add-facts! ensure-environment]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [to-jdbc-varchar-array]]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [puppetlabs.puppetdb.time :refer [now to-timestamp]]))

(defn store-example-resources
  ([] (store-example-resources true))
  ([environment?]
   (jdbc/with-transacted-connection *db*
     (jdbc/insert-multi!
       :resource_params_cache
       [{:resource (sutils/munge-hash-for-storage "01")
         :parameters (sutils/munge-jsonb-for-storage
                       {"ensure" "file"
                        "owner"  "root"
                        "nested" {"foo" "bar"}
                        "boolean" true
                        "numeric" 1337
                        "double" 3.14
                        "group"  "root"
                        "acl"    ["john:rwx" "fred:rwx"]
                        "backslash" "foo\\bar"
                        "double_quote" "foo\"bar"})}
        {:resource (sutils/munge-hash-for-storage "02")
         :parameters (sutils/munge-jsonb-for-storage {})}])
     (jdbc/insert-multi!
       :resource_params
       [{:resource (sutils/munge-hash-for-storage "01") :name "ensure"
         :value (sutils/db-serialize "file")}
        {:resource (sutils/munge-hash-for-storage "01") :name "owner"
         :value (sutils/db-serialize "root")}
        {:resource (sutils/munge-hash-for-storage "01") :name "group"
         :value (sutils/db-serialize "root")}
        {:resource (sutils/munge-hash-for-storage "01") :name "acl"
         :value (sutils/db-serialize ["john:rwx" "fred:rwx"])}
        {:resource (sutils/munge-hash-for-storage "01") :name "nested"
         :value (sutils/db-serialize {"foo" "bar"})}
        {:resource (sutils/munge-hash-for-storage "01") :name "boolean"
         :value (sutils/db-serialize true)}
        {:resource (sutils/munge-hash-for-storage "01") :name "numeric"
         :value (sutils/db-serialize 1337)}
        {:resource (sutils/munge-hash-for-storage "01") :name "double"
         :value (sutils/db-serialize 3.14)}
        {:resource (sutils/munge-hash-for-storage "01") :name "backslash"
         :value (sutils/db-serialize "foo\\bar")}
        {:resource (sutils/munge-hash-for-storage "01") :name "double_quote"
         :value (sutils/db-serialize "foo\"bar")}])
       (add-certnames ["one.local" "two.local"])
       (jdbc/insert-multi!
        :catalogs
         [{:id 1
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
           :environment_id (when environment? (ensure-environment "PROD"))}])

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

       (let [certname-ids (into {} (map (juxt :certname :id)) (jdbc/query-to-vec "SELECT certname, id from certnames"))
             one-cert-id (get certname-ids "one.local")
             two-cert-id (get certname-ids "two.local")]
         (jdbc/insert-multi!
          :catalog_resources
          [{:certname_id one-cert-id :resource (sutils/munge-hash-for-storage "01")
            :type "File" :title "/etc/passwd" :exported false
            :tags (to-jdbc-varchar-array ["one" "two" "æøåۿᚠ𠜎٤"])}
           {:certname_id one-cert-id :resource (sutils/munge-hash-for-storage "02")
            :type "Notify" :title "hello" :exported false
            :tags (to-jdbc-varchar-array [])}
           {:certname_id two-cert-id :resource (sutils/munge-hash-for-storage "01")
            :type "File" :title "/etc/passwd" :exported false
            :tags (to-jdbc-varchar-array ["one" "two"])}
           {:certname_id two-cert-id :resource (sutils/munge-hash-for-storage "02")
            :type "Notify" :title "hello" :exported true :file "/foo/bar" :line 22
            :tags (to-jdbc-varchar-array [])}])))

     {:foo1 {:certname   "one.local"
             :resource   "01"
             :type       "File"
             :title      "/etc/passwd"
             :tags       ["one" "two" "æøåۿᚠ𠜎٤"]
             :exported   false
             :file nil
             :line nil
             :environment (when environment? "DEV")
             :parameters {:ensure "file"
                          :owner  "root"
                          :nested {:foo "bar"}
                          :boolean true
                          :numeric 1337
                          :double 3.14
                          :group  "root"
                          :acl    ["john:rwx" "fred:rwx"]
                          :backslash "foo\\bar"
                          :double_quote "foo\"bar"}}
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
                          :nested {:foo "bar"}
                          :boolean true
                          :numeric 1337
                          :double 3.14
                          :group  "root"
                          :acl    ["john:rwx" "fred:rwx"]
                          :backslash "foo\\bar"
                          :double_quote "foo\"bar"}}
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
