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
        report-hash   (scf-store/report-identity-string basic)]
    (report/validate! basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    (testing "should return reports based on certname"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result ["=" "certname" (:certname basic)])]
        (is (= expected actual))))))



