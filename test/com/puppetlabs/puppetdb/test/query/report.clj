(ns com.puppetlabs.puppetdb.test.query.report
  (:require [com.puppetlabs.puppetdb.query.report :as query])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        com.puppetlabs.puppetdb.testutils.report
        [com.puppetlabs.time :only [to-secs]]
        [clj-time.core :only [now ago days]]))

(use-fixtures :each with-test-db)

;; Begin tests

(deftest test-compile-report-term
  (testing "should successfully compile a valid equality query"
    (is (= (query/compile-report-term ["=" "certname" "foo.local"])
           {:where   "reports.certname = ?"
            :params  ["foo.local"]})))
  (testing "should fail with an invalid equality query"
    (is (thrown-with-msg?
          IllegalArgumentException #"is not a valid query term"
          (query/compile-report-term ["=" "foo" "foo"])))))

(deftest reports-retrieval
  (let [basic         (:basic reports)
        report-hash   (store-report! basic (now))]
    (testing "should return reports based on certname"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result ["=" "certname" (:certname basic)])]
        (is (= expected actual))))))






