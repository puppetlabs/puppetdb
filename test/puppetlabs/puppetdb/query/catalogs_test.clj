(ns puppetlabs.puppetdb.query.catalogs-test
  (:require [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [clj-time.core :refer [now]]
            [clojure.java.io :refer [resource]]))

(use-fixtures :each with-test-db)

(deftest catalog-query
  (let [catalog-str (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
        {:strs [name version transaction-uuid environment] :as catalog} (json/parse-string
                                                                          catalog-str)]
    (testcat/replace-catalog catalog-str)
    (doseq [api-version [:v3 :v4]]
      (testing "get-catalog-info"
        (is (= (str version)  (:version (c/get-catalog-info name))))
        (is (= transaction-uuid (:transaction-uuid (c/get-catalog-info name))))
        (is (= environment (:environment (c/get-catalog-info name)))))
      (testing "catalog-for-node"
        (is (= (testcat/munged-canonical->wire-format api-version (json/parse-string catalog-str true))
               (testcat/munged-canonical->wire-format api-version (c/catalog-for-node api-version name))))))))
