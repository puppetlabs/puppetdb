(ns com.puppetlabs.puppetdb.test.query.event
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.puppetdb.query.event :as query]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
         com.puppetlabs.puppetdb.fixtures
         com.puppetlabs.puppetdb.examples.report
         [clj-time.coerce :only [to-string to-timestamp]]
         [clj-time.core :only [now ago days]]))

(use-fixtures :each with-test-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn store-report!
  [example-report]
  (let [report-hash   (scf-store/report-identity-string example-report)]
    (report/validate! example-report)
    (scf-store/maybe-activate-node! (:certname example-report) (now))
    (scf-store/add-report! example-report (now))
    report-hash))

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


;; TODO: this test probably "belongs" in the scf/storage namespace, but
;; that would prevent me from using some of the report-specific test utility
;; functions that I've built up here.  I suppose I could create a new namespace
;; for those, or just :use this namespace from the storage tests?  the former
;; seems like overkill and the latter just feels weird :)
(deftest resource-events-cleanup
  (testing "should delete all events for reports older than the specified age"
    (let [report1       (assoc (:basic reports)
                                :end-time
                                (to-string (ago (days 5))))
          report1-hash  (store-report! report1)
          report2       (assoc (:basic reports)
                                :end-time
                                (to-string (ago (days 2))))
          report2-hash  (store-report! report2)
          certname      (:certname report1)
          _             (scf-store/delete-reports-older-than! (ago (days 3)))
          expected      #{}
          actual        (resource-events-query-result ["=" "report" report1-hash])]
      (is (= expected actual)))))


