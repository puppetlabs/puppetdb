(ns com.puppetlabs.puppetdb.test.query.report
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.puppetdb.query.report :as query]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
         com.puppetlabs.puppetdb.fixtures
         com.puppetlabs.puppetdb.examples.report
         [com.puppetlabs.time :only [to-secs]]
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

(deftest reports-retrieval
  (let [basic         (:basic reports)
        report-hash   (store-report! basic)]
    (testing "should return reports based on certname"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result ["=" "certname" (:certname basic)])]
        (is (= expected actual))))))

;; TODO: this test probably "belongs" in the scf/storage namespace, but
;; that would prevent me from using some of the report-specific test utility
;; functions that I've built up here.  I suppose I could create a new namespace
;; for those, or just :use this namespace from the storage tests?  the former
;; seems like overkill and the latter just feels weird :)
(deftest report-cleanup
  (testing "should delete reports older than the specified age"
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
          expected      (expected-reports [(assoc report2 :hash report2-hash)])
          actual        (reports-query-result ["=" "certname" certname])]
      (is (= expected actual)))))





