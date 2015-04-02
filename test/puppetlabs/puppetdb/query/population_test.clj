(ns puppetlabs.puppetdb.query.population-test
  (:require [puppetlabs.puppetdb.query.population :as pop]
            [clojure.java.jdbc :as sql]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage :refer [deactivate-node!]]
            [puppetlabs.puppetdb.scf.storage-utils :refer [to-jdbc-varchar-array]]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [now]]))

(use-fixtures :each with-test-db)

(deftest resource-count
  (testing "Counting resources"
    (testing "should return 0 when no resources present"
      (is (= 0 (pop/num-resources))))

    (testing "should only count current resources"
      (sql/insert-records
       :certnames
       {:certname "h1"}
       {:certname "h2"})

      (deactivate-node! "h2")

      (sql/insert-records
       :catalogs
       {:id 1 :hash "c1" :api_version 1 :catalog_version "1" :certname "h1" :producer_timestamp (to-timestamp (now))}
       {:id 2 :hash "c2" :api_version 1 :catalog_version "1" :certname "h2" :producer_timestamp (to-timestamp (now))})

      (sql/insert-records
       :resource_params_cache
       {:resource "1" :parameters nil}
       {:resource "2" :parameters nil}
       {:resource "3" :parameters nil})

      (sql/insert-records
       :catalog_resources
       {:catalog_id 1 :resource "1" :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       ;; c2's resource shouldn't be counted, as they don't correspond to an active node
       {:catalog_id 2 :resource "1" :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource "2" :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource "3" :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

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
      (sql/insert-records
       :certnames
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
      (sql/insert-records
       :certnames
       {:certname "h1"}
       {:certname "h2"})

      (sql/insert-records
       :catalogs
       {:id 1 :hash "c1" :api_version 1 :catalog_version "1" :certname "h1" :producer_timestamp (to-timestamp (now))}
       {:id 2 :hash "c2" :api_version 1 :catalog_version "1" :certname "h2" :producer_timestamp (to-timestamp (now))})

      (sql/insert-records
       :resource_params_cache
       {:resource "1" :parameters nil}
       {:resource "2" :parameters nil}
       {:resource "3" :parameters nil})

      (sql/insert-records
       :catalog_resources
       {:catalog_id 1 :resource "1" :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 2 :resource "1" :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource "2" :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog_id 1 :resource "3" :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

      (let [total  4
            unique 3
            dupes  (/ (- total unique) total)]
        (is (= dupes (pop/pct-resource-duplication))))

      ;; If we remove h2's resources, the only resources left are all
      ;; unique and should result in a duplicate percentage of zero
      (deactivate-node! "h2")
      (is (= 0 (pop/pct-resource-duplication))))))
