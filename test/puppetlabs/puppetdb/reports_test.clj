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
           RuntimeException #":timestamp \(not \(datetime\? \"foo\"\)\)"
           (s/validate report-wireformat-schema (assoc-in report [:resources 0 :timestamp] "foo")))))))

(defn underscore->dash-report-keys [m]
  (->> m
       utils/underscore->dash-keys
       (sp/transform [:resource-events sp/ALL sp/ALL]
                     #(update % 0 utils/underscores->dashes))))

(def v8-example-report
  (-> reports
      :basic
      report-query->wire-v8))

(def v7-example-report
  (-> v8-example-report
      (dissoc :producer)))

(def v6-example-report
  (-> v7-example-report
      (dissoc :code_id :catalog_uuid :cached_catalog_status)))

(def v5-example-report
  (-> reports
      :basic
      (dissoc :code_id :catalog_uuid :cached_catalog_status :producer)
      report-query->wire-v5))

(def v4-example-report
  (-> v5-example-report
      underscore->dash-report-keys
      (dissoc :logs :metrics :noop :producer-timestamp)))

(deftest test-v7-conversion
  (let [v7-report v7-example-report
        v8-report (wire-v7->wire-v8 v7-report)]

    (is (s/validate report-v7-wireformat-schema v7-report))
    (is (s/validate report-wireformat-schema v8-report))))

(deftest test-v6-conversion
  (let [v6-report v6-example-report
        v8-report (wire-v6->wire-v8 v6-report)]

    (is (s/validate report-v6-wireformat-schema v6-report))
    (is (s/validate report-wireformat-schema v8-report))))

(deftest test-v5-conversion
  (let [v5-report v5-example-report
        v8-report (wire-v5->wire-v8 v5-report)]

    (is (s/validate report-v5-wireformat-schema v5-report))
    (is (s/validate report-wireformat-schema v8-report))))

(deftest test-v4-conversion
  (let [current-time (now)
        v8-report (wire-v4->wire-v8 v4-example-report current-time)]

    (is (s/validate report-v4-wireformat-schema v4-example-report))
    (is (s/validate report-wireformat-schema v8-report))
    (is (= current-time (:producer_timestamp v8-report)))))

(deftest test-v3-conversion
  (let [current-time (now)
        v3-report (dissoc v4-example-report :status)
        v8-report (wire-v3->wire-v8 v3-report current-time)]
    (is (s/validate report-v3-wireformat-schema v3-report))
    (is (s/validate report-wireformat-schema (wire-v3->wire-v8 v3-report current-time)))
    (is (= current-time (:producer_timestamp v8-report)))
    (is (nil? (:status v8-report)))))
