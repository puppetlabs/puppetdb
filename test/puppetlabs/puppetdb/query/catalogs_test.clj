(ns puppetlabs.puppetdb.query.catalogs-test
  (:require [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [clojure.java.io :refer [resource]]))

(use-fixtures :each with-test-db)

(deftest catalog-query
  (let [catalog-str (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
        {:strs [certname version transaction_uuid environment] :as catalog} (json/parse-string
                                                                          catalog-str)]
    (testcat/replace-catalog catalog-str)
    (testing "status"
      (is (= (testcat/munged-canonical->wire-format :v5 (json/parse-string catalog-str true))
             (testcat/munged-canonical->wire-format :v5 (c/status :v4 certname)))))))

(def data-seq (-> (slurp "./test-resources/puppetlabs/puppetdb/cli/export/catalog-query-rows.json")
                      (json/parse-string)
                      (keywordize-keys)))
