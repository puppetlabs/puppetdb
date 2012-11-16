(ns com.puppetlabs.puppetdb.test.query.event
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.puppetdb.query.event :as query]
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

(defn expected-resource-event
  [example-resource-event report-hash]
  (-> example-resource-event
    ;; the examples don't have the report-id, but the results from the
    ;; database do... so we need to munge that in.
    (assoc-in [:report] report-hash)
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)))

(defn expected-resource-events
  [example-resource-events report-hash]
  (set (map #(expected-resource-event % report-hash) example-resource-events)))

(defn resource-events-query-result
  [query]
  (set (->> (query/resource-event-query->sql query)
            (query/query-resource-events))))

;; Begin tests

(deftest test-compile-resource-event-term
  (testing "should succesfully compile a valid equality query"
    (is (= (query/compile-resource-event-term ["=" "report" "blah"])
           {:where   "resource_events.report = ?"
            :params  ["blah"]})))
  (testing "should fail with an invalid equality query"
    (is (thrown-with-msg?
          IllegalArgumentException #"is not a valid query term"
          (query/compile-resource-event-term ["=" "foo" "foo"])))))

(deftest resource-events-retrieval
  (let [basic         (:basic reports)
        report-hash   (scf-store/report-identity-string basic)]
    (report/validate! basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    (testing "should return the list of resource events for a given report hash"
      (let [expected  (expected-resource-events (:resource-events basic) report-hash)
            actual    (resource-events-query-result ["=" "report" report-hash])]
        (is (= expected actual))))))


