(ns com.puppetlabs.puppetdb.test.cli.export
  (:require [com.puppetlabs.puppetdb.query.catalog :as c]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalog :as testcat])
  (:use  [clojure.java.io :only [resource]]
         clojure.test
         [com.puppetlabs.puppetdb.fixtures]))



(use-fixtures :each with-test-db)


(deftest export
  (testing "Exporting a JSON catalog"
    (testing "the exported JSON should match the original import JSON"
      (let [original-catalog-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/big-catalog.json"))
            original-catalog      (json/parse-string original-catalog-str)]
        (testcat/replace-catalog original-catalog-str)

         (let [exported-catalog (c/catalog-for-node "myhost.localdomain")]
            (is (= (testcat/munge-catalog-for-comparison original-catalog)
                   (testcat/munge-catalog-for-comparison exported-catalog))))))))



