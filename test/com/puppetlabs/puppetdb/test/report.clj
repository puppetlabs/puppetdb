(ns com.puppetlabs.puppetdb.test.report
  (:use [clojure.test]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.puppetdb.examples.report]
        [com.puppetlabs.puppetdb.report]
        [com.puppetlabs.puppetdb.testutils.report :only [munge-example-report-for-storage]])
  (:require [com.puppetlabs.utils :as utils]
            [cheshire.core :as json]))

(let [report (munge-example-report-for-storage (:basic reports))]

  (deftest test-validate!
    (testing "should accept a valid report"
      (is (= report (validate! 1 report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
            IllegalArgumentException #"Report is missing keys: :certname$"
            (validate! 1 (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
            IllegalArgumentException #":timestamp should be Datetime"
            (validate! 1 (assoc-in report [:resource-events 0 :timestamp] "foo")))))))

