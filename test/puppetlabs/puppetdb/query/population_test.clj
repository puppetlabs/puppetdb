(ns puppetlabs.puppetdb.query.population-test
  (:require [puppetlabs.puppetdb.query.population :as pop]
            [clojure.java.jdbc :as sql]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage :refer [deactivate-node!]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils :refer [to-jdbc-varchar-array]]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [now]]))

(use-fixtures :each with-test-db)

(deftest resource-count
  (testing "Counting resources"
    (testing "should return 0 when no resources present"
      (is (= 0 (pop/num-resources))))

    (testing "should only count current resources"
      (jdbc/insert! :certnames
                    {:certname "h1"}
                    {:certname "h2"})

      (deactivate-node! "h2")

      (jdbc/insert! :catalogs
                    {:id 1 :hash (sutils/munge-hash-for-storage "c1")
                     :api_version 1 :catalog_version "1"
                     :certname "h1" :producer_timestamp (to-timestamp (now))}
                    {:id 2 :hash (sutils/munge-hash-for-storage "c2")
                     :api_version 1 :catalog_version "1"
                     :certname "h2" :producer_timestamp (to-timestamp (now))})

      (jdbc/insert!
       :resource_params_cache
       {:resource (sutils/munge-hash-for-storage "01") :parameters nil}
       {:resource (sutils/munge-hash-for-storage "02") :parameters nil}
       {:resource (sutils/munge-hash-for-storage "03") :parameters nil})

      (jdbc/insert!
       :catalog_resources
       {:catalog_id 1 :resource (sutils/munge-hash-for-storage "01") :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       ;; c2's resource shouldn'sutils/munge-hash-for-storage t be counted, as they don't correspond to an active node
       {:catalog_id 2 :resource (sutils/munge-hash-for-storage "01") :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource (sutils/munge-hash-for-storage "02") :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource (sutils/munge-hash-for-storage "03") :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

      (is (= 3 (pop/num-resources))))

    (testing "should only count resources for active nodes"
      ;; Remove the node from the previous block
      (deactivate-node! "h1")
      (is (= 0 (pop/num-resources))))))

(deftest node-count
  (testing "Counting nodes"
    (testing "should return 0 when no resources present"
      (is (= 0 (pop/num-nodes))))

    (testing "should only count active nodes"
      (jdbc/insert! :certnames
                    {:certname "h1"}
                    {:certname "h2"})

      (is (= 2 (pop/num-nodes)))

      (deactivate-node! "h1")
      (is (= 1 (pop/num-nodes))))))

(deftest resource-dupes
  (testing "Computing resource duplication"
    (testing "should return 0 when no resources present"
      (is (= 0 (pop/pct-resource-duplication))))

    (testing "should equal (total-unique) / total"
      (jdbc/insert! :certnames
                    {:certname "h1"}
                    {:certname "h2"})

      (jdbc/insert!
       :catalogs
       {:id 1 :hash (sutils/munge-hash-for-storage "c1") :api_version 1
        :transaction_uuid (sutils/munge-uuid-for-storage "68b08e2a-eeb1-4322-b241-bfdf151d294b")
        :catalog_version "1" :certname "h1" :producer_timestamp (to-timestamp (now))}
       {:id 2 :hash (sutils/munge-hash-for-storage "c2") :api_version 1
        :transaction_uuid (sutils/munge-uuid-for-storage "68b08e2a-eeb1-4322-b241-bfdf151d294b")
        :catalog_version "1" :certname "h2" :producer_timestamp (to-timestamp (now))})

      (jdbc/insert!
       :resource_params_cache
       {:resource (sutils/munge-hash-for-storage "01") :parameters nil}
       {:resource (sutils/munge-hash-for-storage "02") :parameters nil}
       {:resource (sutils/munge-hash-for-storage "03") :parameters nil})

      (jdbc/insert!
       :catalog_resources
       {:catalog_id 1 :resource  (sutils/munge-hash-for-storage "01") :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 2 :resource  (sutils/munge-hash-for-storage "01") :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource  (sutils/munge-hash-for-storage "02") :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource  (sutils/munge-hash-for-storage "03") :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

      (let [total  4
            unique 3
            dupes  (/ (- total unique) total)]
        (is (= dupes (pop/pct-resource-duplication))))

      ;; If we remove h2's resources, the only resources left are all
      ;; unique and should result in a duplicate percentage of zero
      (deactivate-node! "h2")
      (is (= 0 (pop/pct-resource-duplication))))))
