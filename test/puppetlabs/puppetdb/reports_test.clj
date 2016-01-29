(ns puppetlabs.puppetdb.reports-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.reports :refer :all]
            [schema.core :as s]
            [com.rpl.specter :as sp]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.core :refer [now]]
            [schema.core :as s]))

(let [report (-> (:basic reports)
                 report-query->wire-v7)]

  (deftest test-validate

    (testing "should accept a valid v7 report"
      (is (= report (s/validate report-wireformat-schema report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
           RuntimeException #"Value does not match schema: \{:certname missing-required-key\}$"
           (s/validate report-wireformat-schema (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
           RuntimeException #":timestamp \(not \(datetime\? \"foo\"\)\)"
           (s/validate report-wireformat-schema (assoc-in report [:resources 0 :timestamp] "foo")))))))

(defn underscore->dash-report-keys [m]
  (->> m
       utils/underscore->dash-keys
       (sp/transform [:resource-events sp/ALL sp/ALL]
                     #(update % 0 utils/underscores->dashes))))

(def v5-example-report
  (-> reports
      :basic
      (dissoc :code_id :catalog_uuid :cached_catalog_reason)
      report-query->wire-v5))

(def v4-example-report
  (-> v5-example-report
      underscore->dash-report-keys
      (dissoc :logs :metrics :noop :producer-timestamp)))

(deftest test-v5-conversion
  (let [v5-report v5-example-report
        v7-report (wire-v5->wire-v7 v5-report)]

    (is (s/validate report-v5-wireformat-schema v5-report))
    (is (s/validate report-wireformat-schema v7-report))))

(deftest test-v4-conversion
  (let [current-time (now)
        v7-report (wire-v4->wire-v7 v4-example-report current-time)]

    (is (s/validate report-v4-wireformat-schema v4-example-report))
    (is (s/validate report-wireformat-schema v7-report))
    (is (= current-time (:producer_timestamp v7-report)))))

(deftest test-v3-conversion
  (let [current-time (now)
        v3-report (dissoc v4-example-report :status)
        v7-report (wire-v3->wire-v7 v3-report current-time)]
    (is (s/validate report-v3-wireformat-schema v3-report))
    (is (s/validate report-wireformat-schema (wire-v3->wire-v7 v3-report current-time)))
    (is (= current-time (:producer_timestamp v7-report)))
    (is (nil? (:status v7-report)))))
