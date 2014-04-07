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
        {:strs [name version transaction-uuid environment] :as catalog}    (json/parse-string catalog-str)]
    (testcat/replace-catalog catalog-str)
    (doseq [cmd-version [:v3 :v4]]
      (testing "get-catalog-info"
        (is (= (str version)  (:version (c/get-catalog-info name))))
        (is (= transaction-uuid (:transaction-uuid (c/get-catalog-info name))))
        (is (= environment (:environment (c/get-catalog-info name)))))
      (testing "catalog-for-node"
        (is (= (testcat/munged-canonical->wire-format cmd-version (json/parse-string catalog-str true))
               (testcat/munged-canonical->wire-format cmd-version (c/catalog-for-node cmd-version name))))))))
