(ns com.puppetlabs.puppetdb.test.query.population
  (:require [com.puppetlabs.puppetdb.query.population :as pop]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.puppetdb.scf.storage :only [deactivate-node! to-jdbc-varchar-array]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(deftest resource-count
  (testing "Counting resources"
    (testing "should return 0 when no resources present"
      (is (= 0 (pop/num-resources))))

    (testing "should only count current resources"
      (sql/insert-records
       :certnames
       {:name "h1"})

      (sql/insert-records
       :catalogs
       {:hash "c1" :api_version 1 :catalog_version "1"}
       {:hash "c2" :api_version 1 :catalog_version "1"})

      ;; The catalog "c2" isn't associated with a node
      (sql/insert-records
       :certname_catalogs
       {:certname "h1" :catalog "c1" :timestamp (to-timestamp (now))})

      (sql/insert-records
       :catalog_resources
       {:catalog "c1" :resource "1" :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       ;; c2's resource shouldn't be counted, as they don't correspond to an active node
       {:catalog "c2" :resource "1" :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c1" :resource "2" :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c1" :resource "3" :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

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
       {:name "h1"}
       {:name "h2"})

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
       {:name "h1"}
       {:name "h2"})

      (sql/insert-records
       :catalogs
       {:hash "c1" :api_version 1 :catalog_version "1"}
       {:hash "c2" :api_version 1 :catalog_version "1"})

      (sql/insert-records
       :certname_catalogs
       {:certname "h1" :catalog "c1" :timestamp (to-timestamp (now))}
       {:certname "h2" :catalog "c2" :timestamp (to-timestamp (now))})

      (sql/insert-records
       :catalog_resources
       {:catalog "c1" :resource "1" :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c2" :resource "1" :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c1" :resource "2" :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c1" :resource "3" :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

      (let [total  4
            unique 3
            dupes  (/ (- total unique) total)]
        (is (= dupes (pop/pct-resource-duplication))))

      ;; If we remove h2's resources, the only resources left are all
      ;; unique and should result in a duplicate percentage of zero
      (deactivate-node! "h2")
      (is (= 0 (pop/pct-resource-duplication))))))
