(ns com.puppetlabs.puppetdb.test.query.event
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.event :as event-query]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
         com.puppetlabs.puppetdb.fixtures
         com.puppetlabs.puppetdb.examples.report
         com.puppetlabs.puppetdb.testutils.event
         [clj-time.coerce :only [to-string to-timestamp to-long]]
         [clj-time.core :only [now ago days]]))

(use-fixtures :each with-test-db)

;; Begin tests

(deftest test-compile-resource-event-term
  (testing "should succesfully compile a valid equality query"
    (is (= (query/compile-term  event-query/resource-event-ops ["=" "report" "blah"])
           {:where   "resource_events.report = ?"
            :params  ["blah"]})))
  (testing "should fail with an invalid equality query"
    (is (thrown-with-msg?
          IllegalArgumentException #"foo is not a queryable object for resource events"
          (query/compile-term event-query/resource-event-ops ["=" "foo" "foo"]))))
  (testing "should successfully compile valid inequality queries"
    (let [start-time  "2011-01-01T12:00:01-03:00"
          end-time    "2011-01-01T12:00:03-03:00"]
      (is (= (query/compile-term  event-query/resource-event-ops [">" "timestamp" start-time])
            {:where   "resource_events.timestamp > ?"
             :params  [(to-timestamp start-time)]}))
      (is (= (query/compile-term  event-query/resource-event-ops ["<" "timestamp" end-time])
            {:where   "resource_events.timestamp < ?"
             :params  [(to-timestamp end-time)]}))
      (is (= (query/compile-term  event-query/resource-event-ops
                ["and" [">=" "timestamp" start-time] ["<=" "timestamp" end-time]])
            {:where   "(resource_events.timestamp >= ?) AND (resource_events.timestamp <= ?)"
             :params  [(to-timestamp start-time) (to-timestamp end-time)]}))))
  (testing "should fail with invalid inequality queries"
    (is (thrown-with-msg?
          IllegalArgumentException #"> requires exactly two arguments"
          (query/compile-term event-query/resource-event-ops [">" "timestamp"])))
    (is (thrown-with-msg?
          IllegalArgumentException #"'foo' is not a valid timestamp value"
          (query/compile-term event-query/resource-event-ops [">" "timestamp" "foo"])))
    (is (thrown-with-msg?
          IllegalArgumentException #"> operator does not support object 'resource_type'"
          (query/compile-term event-query/resource-event-ops [">" "resource_type" "foo"])))))

(deftest resource-event-queries
  (let [basic         (:basic reports)
        report-hash   (scf-store/report-identity-string basic)]
    (report/validate! basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    (testing "resource event retrieval"
      (testing "should return the list of resource events for a given report hash"
        (let [expected  (expected-resource-events (:resource-events basic) report-hash)
              actual    (resource-events-query-result ["=" "report" report-hash])]
          (is (= expected actual)))))

    (testing "resource event timestamp queries"
      (testing "should return the list of resource events that occurred before a given time"
        (let [end-time  "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (filter #(> (to-long end-time) (to-long (:timestamp %)))
                              (:resource-events basic))
                            report-hash)
              actual      (resource-events-query-result ["<" "timestamp" end-time])]
          (is (= expected actual))
          (is (= 2 (count actual)))))
      (testing "should return the list of resource events that occurred after a given time"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              expected    (expected-resource-events
                            (filter #(< (to-long start-time) (to-long (:timestamp %)))
                              (:resource-events basic))
                            report-hash)
              actual      (resource-events-query-result [">" "timestamp" start-time])]
          (is (= expected actual))
          (is (= 2 (count actual)))))
      (testing "should return the list of resource events that occurred between a given start and end time"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (filter #(and (< (to-long start-time)
                                             (to-long (:timestamp %)))
                                          (> (to-long end-time)
                                             (to-long (:timestamp %))))
                              (:resource-events basic))
                            report-hash)
              actual      (resource-events-query-result
                            ["and"  [">" "timestamp" start-time]
                                    ["<" "timestamp" end-time]])]
          (is (= expected actual))
          (is (= 1 (count actual)))))
      (testing "should return the list of resource events that occurred between a given start and end time (inclusive)"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (filter #(and (<= (to-long start-time)
                                              (to-long (:timestamp %)))
                                          (>= (to-long end-time)
                                              (to-long (:timestamp %))))
                              (:resource-events basic))
                            report-hash)
              actual      (resource-events-query-result
                            ["and"   [">=" "timestamp" start-time]
                                     ["<=" "timestamp" end-time]])]
          (is (= expected actual))
          (is (= 3 (count actual))))))

      (testing "when querying with a limit"
        (let [num-events (count (:resource-events basic))]
          (testing "should succeed if the number of returned events is less than the limit"
            (is (= num-events
                  (count (resource-events-limited-query-result (inc num-events) ["=" "report" report-hash])))))
          (testing "should fail if the number of returned events would exceed the limit"
            (is (thrown-with-msg?
              IllegalStateException #"Query returns more than the maximum number of results"
              (resource-events-limited-query-result (dec num-events) ["=" "report" report-hash]))))))))





