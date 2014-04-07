(ns com.puppetlabs.puppetdb.test.cli.export
  (:require [com.puppetlabs.puppetdb.query.catalogs :as c]
            [com.puppetlabs.puppetdb.query.reports :as r]
            [com.puppetlabs.puppetdb.query.events :as e]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [com.puppetlabs.puppetdb.testutils.reports :as testrep]
            [com.puppetlabs.puppetdb.examples.reports :refer [report=]]
            [com.puppetlabs.puppetdb.cli.export :as export]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.command.constants :refer [command-names]]
            [com.puppetlabs.puppetdb.testutils.repl :as turepl])
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

        ;; This is explicitly set to v4, as per the current CLI tooling
        (let [exported-catalog (c/catalog-for-node :v4 "myhost.localdomain")]

          (is (= (testcat/munge-catalog-for-comparison :v4 original-catalog)
                 (testcat/munge-catalog-for-comparison :v4 exported-catalog)))))))

  (testing "Exporting a JSON report"
    (testing "the exported JSON should match the original import JSON"
      (let [original-report-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/sample-report.json"))
            original-report      (clojure.walk/keywordize-keys (json/parse-string original-report-str))]
        (testrep/store-example-report! original-report "2012-10-09T22:15:17-07:00")

        ;; This is explicitly set to v3, as per the current CLI tooling
        (let [exported-report (first (r/reports-for-node :v3 "myhost.localdomain"))]
          (is (report= (testrep/munge-report-for-comparison original-report)
                       (testrep/munge-report-for-comparison exported-report)))))))

  (testing "Export metadata"
    (let [{:keys [msg file-suffix contents]} (export/export-metadata)
          metadata (json/parse-string contents true)]
      (is (= {:replace-catalog 3
              :store-report 2
              :replace-facts 1}
             (:command-versions metadata)))
      (is (= ["export-metadata.json"] file-suffix))
      (is (= "Exporting PuppetDB metadata" msg)))))

