(ns com.puppetlabs.puppetdb.test.query.events
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.reports :as report]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.events :as event-query]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
         com.puppetlabs.puppetdb.fixtures
         com.puppetlabs.puppetdb.examples.reports
         [com.puppetlabs.puppetdb.testutils.reports :only [store-example-report! get-events-map]]
         com.puppetlabs.puppetdb.testutils.events
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
        report-hash   (store-example-report! basic (now))
        conf-version  (:configuration-version basic)
        basic-events  (get-events-map basic)]

    (testing "resource event retrieval by report"
      (testing "should return the list of resource events for a given report hash"
        (let [expected  (expected-resource-events (:resource-events basic) report-hash conf-version)
              actual    (resource-events-query-result ["=" "report" report-hash])]
          (is (= actual expected)))))

    (testing "resource event timestamp queries"
      (testing "should return the list of resource events that occurred before a given time"
        (let [end-time  "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (utils/select-values basic-events [1 3])
                            report-hash
                            conf-version)
              actual      (resource-events-query-result ["<" "timestamp" end-time])]
          (is (= actual expected))))
      (testing "should return the list of resource events that occurred after a given time"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              expected    (expected-resource-events
                            (utils/select-values basic-events [2 3])
                            report-hash
                            conf-version)
              actual      (resource-events-query-result [">" "timestamp" start-time])]
          (is (= actual expected))))
      (testing "should return the list of resource events that occurred between a given start and end time"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (utils/select-values basic-events [3])
                            report-hash
                            conf-version)
              actual      (resource-events-query-result
                            ["and"  [">" "timestamp" start-time]
                                    ["<" "timestamp" end-time]])]
          (is (= actual expected))))
      (testing "should return the list of resource events that occurred between a given start and end time (inclusive)"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (utils/select-values basic-events [1 2 3])
                            report-hash
                            conf-version)
              actual      (resource-events-query-result
                            ["and"   [">=" "timestamp" start-time]
                                     ["<=" "timestamp" end-time]])]
          (is (= actual expected)))))

    (testing "when querying with a limit"
      (let [num-events (count (:resource-events basic))]
        (testing "should succeed if the number of returned events is less than the limit"
          (is (= num-events
                (count (resource-events-limited-query-result (inc num-events) ["=" "report" report-hash])))))
        (testing "should fail if the number of returned events would exceed the limit"
          (is (thrown-with-msg?
            IllegalStateException #"Query returns more than the maximum number of results"
            (resource-events-limited-query-result (dec num-events) ["=" "report" report-hash]))))))

    (testing "equality queries"
      (doseq [[field value matches]
                  [[:resource-type    "Notify"                            [1 2 3]]
                   [:resource-title   "notify, yo"                        [1]]
                   [:status           "success"                           [1 2]]
                   [:property         "message"                           [1 2]]
                   [:property         nil                                 [3]]
                   [:old-value        ["what" "the" "woah"]               [1]]
                   [:new-value        "notify, yo"                        [1]]
                   [:message          "defined 'message' as 'notify, yo'" [1 2]]
                   [:message          nil                                 [3]]
                   [:resource-title   "bunk"                              []]
                   [:certname         "foo.local"                         [1 2 3]]
                   [:certname         "bunk.remote"                       []]
                   [:file             "foo.pp"                            [1]]
                   [:file             "bar"                               [3]]
                   [:file             nil                                 [2]]
                   [:line             2                                   [3]]
                   [:line             nil                                 [2]]
                   [:containing-class "Foo"                               [3]]
                   [:containing-class nil                                 [1 2]]]]
        (testing (format "equality query on field '%s'" field)
          (let [expected  (expected-resource-events
                            (utils/select-values basic-events matches)
                            report-hash
                            conf-version)
                query     ["=" (name field) value]
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query))))))

    (testing "'not' queries"
      (doseq [[field value matches]
              [[:resource-type    "Notify"                            []]
               [:resource-title   "notify, yo"                        [2 3]]
               [:status           "success"                           [3]]
               [:property         "message"                           [3]]
               [:property         nil                                 [1 2]]
               [:old-value        ["what" "the" "woah"]               [2 3]]
               [:new-value        "notify, yo"                        [2 3]]
               [:message          "defined 'message' as 'notify, yo'" [3]]
               [:message          nil                                 [1 2]]
               [:resource-title   "bunk"                              [1 2 3]]
               [:certname         "foo.local"                         []]
               [:certname         "bunk.remote"                       [1 2 3]]
               [:file             "foo.pp"                            [2 3]]
               [:file             "bar"                               [1 2]]
               [:file             nil                                 [1 3]]
               [:line             1                                   [2 3]]
               [:line             2                                   [1 2]]
               [:line             nil                                 [1 3]]
               [:containing-class "Foo"                               [1 2]]
               [:containing-class nil                                 [3]]]]
        (testing (format "'not' query on field '%s'" field)
          (let [expected  (expected-resource-events
                            (utils/select-values basic-events matches)
                            report-hash
                            conf-version)
                query     ["not" ["=" (name field) value]]
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query))))))

    (testing "regex queries"
      (doseq [[field value matches]
              [[:resource-type    "otify"                 [1 2 3]]
               [:resource-title   "^[Nn]otify,\\s*yo$"    [1]]
               [:status           "^.ucces."              [1 2]]
               [:property         "^[Mm][\\w\\s]+"        [1 2]]
               [:message          "notify, yo"            [1 2]]
               [:resource-title   "^bunk$"                []]
               [:certname         "^foo\\."               [1 2 3]]
               [:certname         "^.*\\.mydomain\\.com$" []]
               [:file             ".*"                    [1 3]]
               [:file             "\\.pp"                 [1]]
               [:containing-class "[fF]oo"                [3]]]]
        (testing (format "regex query on field '%s'" field)
          (let [expected  (expected-resource-events
                            (utils/select-values basic-events matches)
                            report-hash
                            conf-version)
                query     ["~" (name field) value]
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query))))))

    (testing "negated regex queries"
      (doseq [[field value matches]
              [[:resource-type    "otify"                 []]
               [:resource-title   "^[Nn]otify,\\s*yo$"    [2 3]]
               [:status           "^.ucces."              [3]]
               [:property         "^[Mm][\\w\\s]+"        [3]]
               [:message          "notify, yo"            [3]]
               [:resource-title   "^bunk$"                [1 2 3]]
               [:certname         "^foo\\."               []]
               [:certname         "^.*\\.mydomain\\.com$" [1 2 3]]
               [:file             ".*"                    [2]]
               [:file             "\\.pp"                 [2 3]]
               [:containing-class "[fF]oo"                [1 2]]]]
        (testing (format "negated regex query on field '%s'" field)
          (let [expected  (expected-resource-events
                            (utils/select-values basic-events matches)
                            report-hash
                            conf-version)
                query     ["not" ["~" (name field) value]]
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query))))))

    (testing "compound queries"
      (testing "'or' equality queries"
        (doseq [[terms matches]
                  [[[[:resource-title "notify, yo"]
                     [:status         "skipped"]]       [1 3]]
                   [[[:resource-type  "bunk"]
                     [:resource-title "notify, yar"]]   [2]]
                   [[[:resource-type  "bunk"]
                     [:status         "bunk"]]          []]
                   [[[:new-value      "notify, yo"]
                     [:resource-title "notify, yar"]
                     [:resource-title "hi"]]            [1 2 3]]
                   [[[:file           "foo.pp"]
                     [:line           2]]               [1 3]]]]
          (let [expected    (expected-resource-events
                              (utils/select-values basic-events matches)
                              report-hash
                              conf-version)
                term-fn     (fn [[field value]] ["=" (name field) value])
                query       (vec (cons "or" (map term-fn terms)))
                actual      (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query))))))

      (testing "'and' equality queries"
        (doseq [[terms matches]
                [[[[:resource-type    "Notify"]
                   [:status           "success"]]     [1 2]]
                 [[[:resource-type    "bunk"]
                   [:resource-title   "notify, yar"]] []]
                 [[[:resource-title   "notify, yo"]
                   [:status           "skipped"]]     []]
                 [[[:new-value        "notify, yo"]
                   [:resource-type    "Notify"]
                   [:certname         "foo.local"]]   [1]]
                 [[[:certname         "foo.local"]
                   [:resource-type    "Notify"]]      [1 2 3]]
                 [[[:file             "foo.pp"]
                   [:line             1]]             [1]]
                 [[[:containing-class "Foo"]]         [3]]]]
          (let [expected    (expected-resource-events
                              (utils/select-values basic-events matches)
                              report-hash
                              conf-version)
                term-fn     (fn [[field value]] ["=" (name field) value])
                query       (vec (cons "and" (map term-fn terms)))
                actual      (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query)))))

      (testing "nested compound queries"
        (doseq [[query matches]
                [[["and"
                    ["or"
                      ["=" "resource-title" "hi"]
                      ["=" "resource-title" "notify, yo"]]
                    ["=" "status" "success"]]               [1]]
                 [["or"
                    ["and"
                      ["=" "resource-title" "hi"]
                      ["=" "status" "success"]]
                    ["and"
                      ["=" "resource-type" "Notify"]
                      ["=" "property" "message"]]]          [1 2]]
                 [["or"
                    ["and"
                      ["=" "file" "foo.pp"]
                      ["=" "line" 1]]
                    ["=" "line" 2]]                         [1 3]]]]
          (let [expected  (expected-resource-events
                            (utils/select-values basic-events matches)
                            report-hash
                            conf-version)
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query)))))

      (testing "compound queries with both equality and inequality"
        (doseq [[query matches]
                [[["and"
                    ["=" "status" "success"]
                    ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [1]]
                 [["or"
                    ["=" "status" "skipped"]
                    ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [1 3]]]]
          (let [expected  (expected-resource-events
                            (utils/select-values basic-events matches)
                            report-hash
                            conf-version)
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query)))))))

(deftest latest-report-resource-event-queries
  (let [basic1        (:basic reports)
        events1       (get-events-map basic1)
        report-hash1  (store-example-report! basic1 (now))
        conf-version1 (:configuration-version basic1)

        basic2        (:basic2 reports)
        events2       (get-events-map basic2)
        report-hash2  (store-example-report! basic2 (now))
        conf-version2 (:configuration-version basic2)]

    (testing "retrieval of events for latest report only"
      (testing "applied to entire query"
        (let [expected  (expected-resource-events (:resource-events basic2) report-hash2 conf-version2)
              actual    (resource-events-query-result ["=" "latest-report?" true])]
          (is (= actual expected))))
      (testing "applied to subquery"
        (let [expected  (expected-resource-events (utils/select-values events2 [5 6]) report-hash2 conf-version2)
              actual    (resource-events-query-result ["and" ["=" "resource-type" "File"] ["=" "latest-report?" true]])]
          (is (= actual expected)))))

    (testing "retrieval of events prior to latest report"
      (testing "applied to entire query"
        (let [expected  (expected-resource-events (:resource-events basic1) report-hash1 conf-version1)
              actual    (resource-events-query-result ["=" "latest-report?" false])]
          (is (= actual expected))))
      (testing "applied to subquery"
        (let [expected  (expected-resource-events (utils/select-values events1 [1 2]) report-hash1 conf-version1)
              actual    (resource-events-query-result ["and" ["=" "status" "success"] ["=" "latest-report?" false]])]
          (is (= actual expected)))))

    (testing "compound latest report"
      (let [results1  (expected-resource-events (utils/select-values events1 [3]) report-hash1 conf-version1)
            results2  (expected-resource-events (utils/select-values events2 [5 6]) report-hash2 conf-version2)
            expected  (clojure.set/union results1 results2)
            actual    (resource-events-query-result ["or"
                                                      ["and" ["=" "status" "skipped"] ["=" "latest-report?" false]]
                                                      ["and" ["=" "message" "created"] ["=" "latest-report?" true]]])]
        (is (= actual expected))))))


(deftest distinct-resource-event-queries
  (let [basic1        (:basic reports)
        events1       (get-events-map basic1)
        report-hash1  (store-example-report! basic1 (now))
        conf-version1 (:configuration-version basic1)

        basic3        (:basic3 reports)
        events3       (get-events-map basic3)
        report-hash3  (store-example-report! basic3 (now))
        conf-version3 (:configuration-version basic3)]
    (testing "retrieval of events for distinct resources only"
      (let [expected  (expected-resource-events (:resource-events basic3) report-hash3 conf-version3)
            actual    (resource-events-query-result ["=" "certname" "foo.local"] {} {:distinct-resources? true})]
        (is (= (count events3) (count actual)))
        (is (= actual expected))))))
