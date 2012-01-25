(ns com.puppetlabs.cmdb.test.query.facts
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.query.facts :as facts]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
         ring.mock.request
         [com.puppetlabs.cmdb.testutils :only [test-db]]
         [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

(def *db* nil)

(use-fixtures :each (fn [f]
                      (let [db (test-db)]
                        (binding [*db* db]
                          (sql/with-connection db
                            (migrate!)
                            (f))))))

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
       (is (= (facts/facts-for-node *db* certname) facts)))
    (testing "without facts present for a node"
       (is (= (facts/facts-for-node *db* "imaginary_node") {})))))
