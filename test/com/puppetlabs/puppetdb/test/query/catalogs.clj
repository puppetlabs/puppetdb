(ns com.puppetlabs.puppetdb.test.query.catalogs
  (:require [com.puppetlabs.puppetdb.examples           :as examples]
            [com.puppetlabs.puppetdb.query.catalogs     :as c]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [cheshire.core                              :as json])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        [clj-time.core :only [now]]
        [clojure.java.io :only [resource]]))

(use-fixtures :each with-test-db)

(deftest catalog-query
  (let [catalog-str      (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/tiny-catalog.json"))
        catalog          (json/parse-string catalog-str)
        certname         (get-in catalog ["data" "name"])
        catalog-version  (str (get-in catalog ["data" "version"]))
        transaction-uuid (get-in catalog ["data" "transaction-uuid"])]
    (testcat/replace-catalog catalog-str)
    (testing "get-catalog-info"
      (is (= catalog-version  (:catalog-version (c/get-catalog-info certname))))
      (is (= transaction-uuid (:transaction-uuid (c/get-catalog-info certname)))))
    (testing "catalog-for-node"
      (is (= (testcat/munge-catalog-for-comparison catalog)
             (testcat/munge-catalog-for-comparison (c/catalog-for-node certname)))))))
