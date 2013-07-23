(ns com.puppetlabs.puppetdb.test.report
  (:use [clojure.test]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.puppetdb.examples.report]
        [com.puppetlabs.puppetdb.report]
        [com.puppetlabs.puppetdb.testutils.report
          :only [munge-example-report-for-storage
                 munge-v2-example-report-to-v1
                 munge-v1-example-report-to-v2]])
  (:require [com.puppetlabs.utils :as utils]
            [cheshire.core :as json]))

(let [report (munge-example-report-for-storage (:basic reports))]

  (deftest test-validate!

    (testing "should accept a valid v1 report"
      (let [v1-report (munge-v2-example-report-to-v1 report)
            v2-report (munge-v1-example-report-to-v2 v1-report)]
        (is (= v2-report (validate! 1 v1-report)))))

    ;; TODO: uncomment this as soon as we've added :file to v2-new-event-fields
;    (testing "should fail when a v1 report has a v2 key"
;      (let [add-key-fn              (fn [event] (assoc event :file "/tmp/foo"))
;            v1-report               (munge-v2-example-report-to-v1 report)
;            v1-report-with-v2-key   (update-in
;                                      v1-report
;                                      [:resource-events]
;                                      #(mapv add-key-fn %))]
;        (is (thrown-with-msg?
;            IllegalArgumentException #"ResourceEvent has unknown keys: :file.*version 1"
;            (validate! 1 v1-report-with-v2-key)))))

    (testing "should accept a valid v2 report"
      (is (= report (validate! 2 report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
            IllegalArgumentException #"Report is missing keys: :certname$"
            (validate! 2 (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
            IllegalArgumentException #":timestamp should be Datetime"
            (validate! 2 (assoc-in report [:resource-events 0 :timestamp] "foo")))))))

