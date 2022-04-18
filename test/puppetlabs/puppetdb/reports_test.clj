(ns puppetlabs.puppetdb.reports-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer [reports
                                                          v4-report
                                                          v5-report
                                                          v6-report
                                                          v7-report]]
            [puppetlabs.puppetdb.reports :refer :all]
            [com.rpl.specter :as sp]
            [puppetlabs.puppetdb.utils.string-formatter :as formatter]
            [puppetlabs.puppetdb.time :refer [now]]
            [schema.core :as s]))

(let [report (-> (:basic reports)
                 report-query->wire-v8)]

  (deftest test-validate

    (testing "should accept a valid v8 report"
      (is (= report (s/validate report-wireformat-schema report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
           RuntimeException #"Value does not match schema: \{:certname missing-required-key\}$"
           (s/validate report-wireformat-schema (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
           RuntimeException #":timestamp \(not \(matches-some-precondition\? \"foo\"\)\)"
           (s/validate report-wireformat-schema (assoc-in report [:resources 0 :timestamp] "foo")))))))

(defn underscore->dash-report-keys [m]
  (->> m
       formatter/underscore->dash-keys
       (sp/transform [:resource-events sp/ALL sp/ALL]
                     #(update % 0 formatter/underscores->dashes))))

(deftest test-v7-conversion
  (let [v8-report (wire-v7->wire-v8 v7-report)]
    (is (s/validate report-wireformat-schema v8-report))))

(deftest test-v6-conversion
  (let [v8-report (wire-v6->wire-v8 v6-report)]
    (is (s/validate report-wireformat-schema v8-report))))

(deftest test-v5-conversion
  (let [v8-report (wire-v5->wire-v8 v5-report)]
    (is (s/validate report-wireformat-schema v8-report))))

(deftest test-v4-conversion
  (let [current-time (now)
        v8-report (wire-v4->wire-v8 v4-report current-time)]
    (is (s/validate report-wireformat-schema v8-report))
    (is (= current-time (:producer_timestamp v8-report)))))

(deftest test-v3-conversion
  (let [current-time (now)
        v3-report (dissoc v4-report :status)
        v8-report (wire-v3->wire-v8 v3-report current-time)]
    (is (s/validate report-v3-wireformat-schema v3-report))
    (is (s/validate report-wireformat-schema v8-report))
    (is (= current-time (:producer_timestamp v8-report)))
    (is (nil? (:status v8-report)))))
