(ns com.puppetlabs.puppetdb.test.query.events
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.reports :as report]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.events :as event-query]
            [puppetlabs.kitchensink.core :as kitchensink])
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
  (let [basic             (store-example-report! (:basic reports) (now))
        report-hash       (:hash basic)
        basic-events      (get-in reports [:basic :resource-events])
        basic-events-map  (get-events-map (:basic reports))]

    (testing "resource event retrieval by report"
      (testing "should return the list of resource events for a given report hash"
        (let [expected  (expected-resource-events basic-events basic)
              actual    (resource-events-query-result ["=" "report" report-hash])]
          (is (= actual expected)))))

    (testing "resource event timestamp queries"
      (testing "should return the list of resource events that occurred before a given time"
        (let [end-time  "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (kitchensink/select-values basic-events-map [1 3])
                            basic)
              actual      (resource-events-query-result ["<" "timestamp" end-time])]
          (is (= actual expected))))
      (testing "should return the list of resource events that occurred after a given time"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              expected    (expected-resource-events
                            (kitchensink/select-values basic-events-map [2 3])
                            basic)
              actual      (resource-events-query-result [">" "timestamp" start-time])]
          (is (= actual expected))))
      (testing "should return the list of resource events that occurred between a given start and end time"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (kitchensink/select-values basic-events-map [3])
                            basic)
              actual      (resource-events-query-result
                            ["and"  [">" "timestamp" start-time]
                                    ["<" "timestamp" end-time]])]
          (is (= actual expected))))
      (testing "should return the list of resource events that occurred between a given start and end time (inclusive)"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"
              expected    (expected-resource-events
                            (kitchensink/select-values basic-events-map [1 2 3])
                            basic)
              actual      (resource-events-query-result
                            ["and"   [">=" "timestamp" start-time]
                                     ["<=" "timestamp" end-time]])]
          (is (= actual expected)))))

    (testing "when querying with a limit"
      (let [num-events (count basic-events)]
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
                            (kitchensink/select-values basic-events-map matches)
                            basic)
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
                            (kitchensink/select-values basic-events-map matches)
                            basic)
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
                            (kitchensink/select-values basic-events-map matches)
                            basic)
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
                            (kitchensink/select-values basic-events-map matches)
                            basic)
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
                              (kitchensink/select-values basic-events-map matches)
                              basic)
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
                              (kitchensink/select-values basic-events-map matches)
                              basic)
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
                            (kitchensink/select-values basic-events-map matches)
                            basic)
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
                            (kitchensink/select-values basic-events-map matches)
                            basic)
                actual    (resource-events-query-result query)]
            (is (= actual expected)
              (format "Results didn't match for query '%s'" query)))))))

(deftest latest-report-resource-event-queries
  (let [basic1        (store-example-report! (:basic reports) (now))
        report-hash1  (:hash basic1)
        events1       (get-in reports [:basic :resource-events])
        events1-map   (get-events-map (:basic reports))

        basic2        (store-example-report! (:basic2 reports) (now))
        report2-hash  (:hash basic2)
        events2       (get-in reports [:basic2 :resource-events])
        events2-map   (get-events-map (:basic2 reports))]

    (testing "retrieval of events for latest report only"
      (testing "applied to entire query"
        (let [expected  (expected-resource-events events2 basic2)
              actual    (resource-events-query-result ["=" "latest-report?" true])]
          (is (= actual expected))))
      (testing "applied to subquery"
        (let [expected  (expected-resource-events (kitchensink/select-values events2-map [5 6]) basic2)
              actual    (resource-events-query-result ["and" ["=" "resource-type" "File"] ["=" "latest-report?" true]])]
          (is (= actual expected)))))

    (testing "retrieval of events prior to latest report"
      (testing "applied to entire query"
        (let [expected  (expected-resource-events events1 basic1)
              actual    (resource-events-query-result ["=" "latest-report?" false])]
          (is (= actual expected))))
      (testing "applied to subquery"
        (let [expected  (expected-resource-events (kitchensink/select-values events1-map [1 2]) basic1)
              actual    (resource-events-query-result ["and" ["=" "status" "success"] ["=" "latest-report?" false]])]
          (is (= actual expected)))))

    (testing "compound latest report"
      (let [results1  (expected-resource-events (kitchensink/select-values events1-map [3]) basic1)
            results2  (expected-resource-events (kitchensink/select-values events2-map [5 6]) basic2)
            expected  (clojure.set/union results1 results2)
            actual    (resource-events-query-result ["or"
                                                      ["and" ["=" "status" "skipped"] ["=" "latest-report?" false]]
                                                      ["and" ["=" "message" "created"] ["=" "latest-report?" true]]])]
        (is (= actual expected))))))

(deftest distinct-resource-event-queries
  (let [basic1        (store-example-report! (:basic reports) (now))
        basic3        (store-example-report! (:basic3 reports) (now))
        report-hash3  (:hash basic3)
        events3       (get-in reports [:basic3 :resource-events])]
    (testing "retrieval of events for distinct resources only"
      (let [expected  (expected-resource-events events3 basic3)
            actual    (resource-events-query-result ["=" "certname" "foo.local"] {} {:distinct-resources? true})]
        (is (= (count events3) (count actual)))
        (is (= actual expected))))))

(deftest paging-results
  (let [basic4        (store-example-report! (:basic4 reports) (now))
        events        (get-in reports [:basic4 :resource-events])
        event-count   (count events)
        select-values #(reverse (kitchensink/select-values (get-events-map (:basic4 reports)) %))]

    (testing "include total results count"
      (let [actual (:count (raw-resource-events-query-result [">" "timestamp" 0] {:count? true}))]
        (is (= actual event-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 event-count]]]
        (let [results (resource-events-query-result [">" "timestamp" 0] {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order-by"
              (resource-events-query-result
                [">" "timestamp" 0]
                {:order-by [[:invalid-field :ascending]]}))))

      (testing "numerical fields"
        (doseq [[order expected-events] [[:ascending  [10 11 12]]
                                         [:descending [12 11 10]]]]
          (testing order
            (let [expected (raw-expected-resource-events (select-values expected-events) basic4)
                  actual   (:result (raw-resource-events-query-result
                                      [">" "timestamp" 0]
                                      {:order-by [[:line order]]}))]
              (is (= actual expected))))))

      (testing "alphabetical fields"
        (doseq [[order expected-events] [[:ascending  [10 11 12]]
                                         [:descending [12 11 10]]]]
          (testing order
            (let [expected (raw-expected-resource-events (select-values expected-events) basic4)
                  actual   (:result (raw-resource-events-query-result
                                      [">" "timestamp" 0]
                                      {:order-by [[:file order]]}))]
              (is (= actual expected))))))

      (testing "timestamp fields"
        (doseq [[order expected-events] [[:ascending  [10 11 12]]
                                         [:descending [12 11 10]]]]
          (testing order
            (let [expected (raw-expected-resource-events (select-values expected-events) basic4)
                  actual   (:result (raw-resource-events-query-result
                                      [">" "timestamp" 0]
                                      {:order-by [[:timestamp order]]}))]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[status-order title-order] expected-events] [[[:descending :ascending] [11 10 12]]
                                                              [[:ascending :descending] [12 10 11]]]]
          (testing (format "status %s resource-title %s" status-order title-order)
            (let [expected (raw-expected-resource-events (select-values expected-events) basic4)
                  actual   (:result (raw-resource-events-query-result
                                      [">" "timestamp" 0]
                                      {:order-by [[:status status-order]
                                                  [:resource-title title-order]]}))]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [[:ascending  [[0 [10 11 12]]
                                                       [1 [11 12]]
                                                       [2 [12]]
                                                       [3 []]]]
                                          [:descending [[0 [12 11 10]]
                                                       [1 [11 10]]
                                                       [2 [10]]
                                                       [3 []]]]]]
        (testing order
          (doseq [[offset expected-events] expected-sequences]
            (let [expected (raw-expected-resource-events (select-values expected-events) basic4)
                  actual   (:result (raw-resource-events-query-result
                                      [">" "timestamp" 0]
                                      {:order-by [[:line order]] :offset offset}))]
              (is (= actual expected)))))))))
