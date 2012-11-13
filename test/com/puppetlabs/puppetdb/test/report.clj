(ns com.puppetlabs.puppetdb.test.report
  (:use [clojure.test]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.puppetdb.examples.report]
        [com.puppetlabs.puppetdb.report])
  (:require [com.puppetlabs.utils :as utils]
            [cheshire.core :as json]))

(let [report (:basic reports)]

  (deftest test-validate!
    (testing "should accept a valid report"
      (is (= report (validate! report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
            IllegalArgumentException #"Report is missing keys: :certname$"
            (validate! (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
            IllegalArgumentException #":timestamp should be Datetime"
            (validate! (assoc-in report [:resource-events 0 :timestamp] "foo")))))))

