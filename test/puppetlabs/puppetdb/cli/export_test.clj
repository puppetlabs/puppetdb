(ns puppetlabs.puppetdb.cli.export-test
  (:require [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.query.reports :as r]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [puppetlabs.puppetdb.testutils.reports :as testrep]
            [puppetlabs.puppetdb.cli.export :as export]
            [clojure.java.io :refer [resource]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]))

(use-fixtures :each with-test-db)

(deftest export
  (testing "Exporting a JSON catalog"
    (testing "the exported JSON should match the original import JSON"
      (let [original-catalog-str (slurp (resource "puppetlabs/puppetdb/cli/export/big-catalog.json"))
            original-catalog      (json/parse-string original-catalog-str)]
        (testcat/replace-catalog original-catalog-str)

        (let [exported-catalog (c/status :v4 "myhost.localdomain")]

          (is (= (testcat/munge-catalog-for-comparison :v6 original-catalog)
                 (testcat/munge-catalog-for-comparison :v6 exported-catalog)))))))

  (testing "Exporting a JSON report"
    (testing "the exported JSON should match the original import JSON"
      (let [original-report-str (slurp (resource "puppetlabs/puppetdb/cli/export/sample-report.json"))
            original-report      (clojure.walk/keywordize-keys (json/parse-string original-report-str))]
        (testrep/store-example-report! original-report "2012-10-09T22:15:17-07:00")

        (let [exported-report (first (r/reports-for-node :v4 "myhost.localdomain"))]
          (is (= (testrep/munge-report-for-comparison original-report)
                 (testrep/munge-report-for-comparison exported-report)))))))

  (testing "Export metadata"
    (let [{:keys [msg file-suffix contents]} (export/export-metadata)
          metadata (json/parse-string contents true)]
      (is (= {:replace_catalog 6
              :store_report 5
              :replace_facts 4}
             (:command_versions metadata)))
      (is (= ["export-metadata.json"] file-suffix))
      (is (= "Exporting PuppetDB metadata" msg)))))
