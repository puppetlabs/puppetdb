(ns com.puppetlabs.puppetdb.test.query.population
  (:require [com.puppetlabs.puppetdb.query.population :as pop]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        [com.puppetlabs.puppetdb.scf.storage :only [to-jdbc-varchar-array]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

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
       :catalog_resources
       {:catalog "c1" :resource "1" :type "Foo" :title "Bar" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c2" :resource "1" :type "Foo" :title "Baz" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c1" :resource "2" :type "Foo" :title "Boo" :exported true :tags (to-jdbc-varchar-array [])}
       {:catalog "c1" :resource "3" :type "Foo" :title "Goo" :exported true :tags (to-jdbc-varchar-array [])})

      (let [total  4
            unique 3
            dupes  (/ (- total unique) total)]
        (= dupes (pop/pct-resource-duplication))))))
