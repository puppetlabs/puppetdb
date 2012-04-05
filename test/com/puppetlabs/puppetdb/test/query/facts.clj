(ns com.puppetlabs.puppetdb.test.query.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.query.facts :as facts])
  (:use clojure.test
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(deftest facts-for-node
  (let [certname "some_certname"
        facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (scf-store/add-certname! certname)
    (scf-store/add-facts! certname facts (now))
    (testing "with facts present for a node"
       (is (= (facts/facts-for-node certname) facts)))
    (testing "without facts present for a node"
       (is (= (facts/facts-for-node "imaginary_node") {})))
    (testing "after deleting facts for a node"
      (scf-store/delete-facts! certname)
      (is (= (facts/facts-for-node certname) {})))))
