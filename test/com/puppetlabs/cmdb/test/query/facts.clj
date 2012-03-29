(ns com.puppetlabs.cmdb.test.query.facts
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.query.facts :as facts])
  (:use clojure.test
        [com.puppetlabs.cmdb.fixtures]))

(use-fixtures :each with-test-db)

(deftest facts-for-node
  (let [certname "some_certname"
        facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (scf-store/add-certname! certname)
    (scf-store/add-facts! certname facts)
    (testing "with facts present for a node"
       (is (= (facts/facts-for-node certname) facts)))
    (testing "without facts present for a node"
       (is (= (facts/facts-for-node "imaginary_node") {})))))
