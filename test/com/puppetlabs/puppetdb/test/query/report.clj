(ns com.puppetlabs.puppetdb.test.query.report
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.puppetdb.query.report :as query]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
         com.puppetlabs.puppetdb.fixtures
         com.puppetlabs.puppetdb.examples.report
         [clj-time.coerce :only [to-timestamp]]
         [clj-time.core :only [now]]))

(use-fixtures :each with-test-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expected-report
  [example-report]
  (utils/mapvals
    ;; we need to map the datetime fields to timestamp objects for comparison
    to-timestamp
    [:start-time :end-time]
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc example-report :resource-events)))

(defn expected-reports
  [example-reports]
  (map expected-report example-reports))

(defn reports-query-result
  [query]
  (vec (->> (query/report-query->sql query)
            (query/query-reports)
            ;; the example reports don't have a receive time (because this is
            ;; calculated by the server), so we remove this field from the response
            ;; for test comparison
            (map #(dissoc % :receive-time)))))

(defn expected-resource-event
  [example-resource-event report-id]
  (-> example-resource-event
    ;; the examples don't have the report-id, but the results from the
    ;; database do... so we need to munge that in.
    (assoc-in [:report-id] report-id)
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)))

(defn expected-resource-events
  [example-resource-events report-id]
  (set (map #(expected-resource-event % report-id) example-resource-events)))

(defn resource-events-query-result
  [query]
  (set (->> (query/resource-event-query->sql query)
            (query/query-resource-events))))

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

(deftest test-compile-resource-event-term
  (testing "should succesfully compile a valid equality query"
    (is (= (query/compile-resource-event-term ["=" "report-id" "blah"])
           {:where   "resource_events.report_id = ?"
            :params  ["blah"]})))
  (testing "should fail with an invalid equality query"
    (is (thrown-with-msg?
          IllegalArgumentException #"is not a valid query term"
          (query/compile-resource-event-term ["=" "foo" "foo"])))))

(deftest resource-events-retrieval
  (let [report-id  (utils/uuid)
        basic      (:basic reports)]
    (report/validate! basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic report-id (now))

    (testing "should return reports based on certname"
      (let [expected  (expected-reports [(assoc basic :id report-id)])
            actual    (reports-query-result ["=" "certname" (:certname basic)])]
        (is (= expected actual))))

    (testing "should return the list of resource events for a given report id"
      (let [expected  (expected-resource-events (:resource-events basic) report-id)
            actual    (resource-events-query-result ["=" "report-id" report-id])]
        (is (= expected actual))))))


