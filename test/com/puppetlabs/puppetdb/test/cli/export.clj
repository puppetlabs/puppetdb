(ns com.puppetlabs.puppetdb.test.cli.export
  (:require [com.puppetlabs.puppetdb.query.catalogs :as c]
            [com.puppetlabs.puppetdb.query.reports :as r]
            [com.puppetlabs.puppetdb.query.events :as e]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [com.puppetlabs.puppetdb.testutils.reports :as testrep]
            [com.puppetlabs.puppetdb.cli.export :as export])
  (:use  [clojure.java.io :only [resource]]
         clojure.test
         [com.puppetlabs.puppetdb.fixtures]
         [com.puppetlabs.puppetdb.catalogs :only [catalog-version]]))

(use-fixtures :each with-test-db)

(deftest export
  (testing "Exporting a JSON catalog"
    (testing "the exported JSON should match the original import JSON"
      (let [original-catalog-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/big-catalog.json"))
            original-catalog      (json/parse-string original-catalog-str)]
        (testcat/replace-catalog original-catalog-str)

         (let [exported-catalog (c/catalog-for-node "myhost.localdomain")]
            (is (= (testcat/munge-catalog-for-comparison original-catalog)
                   (testcat/munge-catalog-for-comparison exported-catalog)))))))

  (testing "Exporting a JSON report"
    (testing "the exported JSON should match the original import JSON"
      (let [original-report-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/sample-report.json"))
            original-report      (clojure.walk/keywordize-keys (json/parse-string original-report-str))]
        (testrep/store-example-report! original-report "2012-10-09T22:15:17-07:00")

        (let [exported-report (first (r/reports-for-node "myhost.localdomain"))]
          (is (= (testrep/munge-report-for-comparison original-report)
                 (testrep/munge-report-for-comparison exported-report)))))))

  (testing "Export metadata"
    (is (= {:replace-catalog catalog-version
            :store-report 2}
          (:command-versions export/export-metadata)))))
