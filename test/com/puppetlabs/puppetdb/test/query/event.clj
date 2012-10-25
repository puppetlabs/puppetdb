(ns com.puppetlabs.puppetdb.test.query.event
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
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc example-report :resource-events)
    [:start-time :end-time]))

(defn expected-reports
  [example-reports]
  (map expected-report example-reports))

(defn reports-query-result
  [query report-id]
  (vec (->> (query/report-query->sql query report-id)
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
  [query report-id]
  (set (->> (query/resource-event-query->sql query report-id)
            (query/query-resource-events))))

;; Begin tests

(deftest resource-events-retrieval
  (let [report-id  (utils/uuid)
        basic     (assoc-in (:basic reports) [:id] report-id)]
    (report/validate basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    (testing "should return all reports if no params are passed"
      (let [expected  (expected-reports [basic])
            actual    (reports-query-result nil nil)]
        (is (= expected actual))))

    (testing "should return a report based on id"
      (let [expected  (expected-reports [basic])
            actual    (reports-query-result nil report-id)]
        (is (= expected actual))))


    ;; TODO: break this up into events and reports in separate files?

    (testing "should return the list of resource events for a given report id"
      (let [expected  (expected-resource-events (:resource-events basic) report-id)
            actual    (resource-events-query-result nil (:report-id basic))]
        (is (= expected actual))))))


