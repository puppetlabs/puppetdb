(ns puppetlabs.puppetdb.reports-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.reports :refer :all]
            [schema.core :as s]
            [puppetlabs.puppetdb.testutils.reports :refer [munge-example-report-for-storage]]
            [com.rpl.specter :as sp]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.core :refer [now]]
            [schema.core :as s]))

(let [report (munge-example-report-for-storage (:basic reports))]

  (deftest test-validate

    (testing "should accept a valid v5 report"
      (is (= report (s/validate report-wireformat-schema report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
           RuntimeException #"Value does not match schema: \{:certname missing-required-key\}$"
           (s/validate report-wireformat-schema (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
           RuntimeException #":timestamp \(not \(datetime\? \"foo\"\)\)"
           (s/validate report-wireformat-schema (assoc-in report [:resource_events 0 :timestamp] "foo")))))))

(deftest test-sanitize-events
  (testing "ensure extraneous keys are removed"
    (let [test-data {:containment_path
                     ["Stage[main]"
                      "My_pg"
                      "My_pg::Extension[puppetdb:pg_stat_statements]"
                      "Postgresql_psql[create extension pg_stat_statements on puppetdb]"]
                     :new_value "CREATE EXTENSION pg_stat_statements"
                     :message
                     "command changed '' to 'CREATE EXTENSION pg_stat_statements'"
                     :old_value nil
                     :status "success"
                     :line 16
                     :property "command"
                     :timestamp "2014-01-09T17:52:56.795Z"
                     :resource_type "Postgresql_psql"
                     :resource_title  "create extension pg_stat_statements on puppetdb"
                     :file  "/etc/puppet/modules/my_pg/manifests/extension.pp"
                     :extradata  "foo"}
          santized (sanitize-events [test-data])
          expected [(dissoc test-data "extradata")]]
      (= santized expected))))

(deftest test-sanitize-report
  (testing "no action on valid reports"
    (let [test-data (:basic reports)]
      (= (sanitize-report test-data) test-data))))

(defn underscore->dash-report-keys [m]
  (->> m
       utils/underscore->dash-keys
       (sp/update [:resource-events sp/ALL sp/ALL]
                  #(update % 0 utils/underscores->dashes))))

(def v4-example-report
  (-> reports
      :basic
      munge-example-report-for-storage
      underscore->dash-report-keys
      (dissoc :logs :metrics :noop :producer-timestamp)))

(deftest test-v5-conversion
  (let [current-time (now)
        v5-report (wire-v4->wire-v5 v4-example-report current-time)]

    (is (s/validate report-v4-wireformat-schema v4-example-report))
    (is (s/validate report-wireformat-schema v5-report))
    (is (= current-time (:producer_timestamp v5-report)))))

(deftest test-v4-conversion
  (let [current-time (now)
        v3-report (dissoc v4-example-report :status)
        v5-report (wire-v3->wire-v5 v3-report current-time)]
    (is (s/validate report-v3-wireformat-schema v3-report))
    (is (s/validate report-wireformat-schema (wire-v3->wire-v5 v3-report current-time)))
    (is (= current-time (:producer_timestamp v5-report)))
    (is (nil? (:status v5-report)))))
