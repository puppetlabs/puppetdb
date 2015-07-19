(ns puppetlabs.puppetdb.query.events-test
  (:require [puppetlabs.puppetdb.query :as query]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report! get-events-map]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils.events :refer :all]
            [puppetlabs.puppetdb.testutils :refer [deftestseq select-values']]
            [clj-time.coerce :refer [to-string to-timestamp to-long]]
            [clj-time.core :refer [now ago days]]))

(use-fixtures :each with-test-db)

(def distinct-resource-events (comp set query-resource-events))

(def versions [:v4])

;; Begin tests

(deftest test-compile-resource-event-term
  (let [version :v4]
    (let [ops (query/resource-event-ops version)]
      (testing "should succesfully compile a valid equality query"
        (is (= (query/compile-term ops ["=" "report" "blah"])
               {:where   (format "%s = ?" (sutils/sql-hash-as-str "latest_events.hash"))
                :params  ["blah"]})))
      (testing "should fail with an invalid equality query"
        (is (thrown-with-msg?
             IllegalArgumentException (re-pattern (str "'foo' is not a queryable object for version " (last (name version))))
             (query/compile-term ops ["=" "foo" "foo"]))))
      (testing "should successfully compile valid inequality queries"
        (let [start-time  "2011-01-01T12:00:01-03:00"
              end-time    "2011-01-01T12:00:03-03:00"]
          (is (= (query/compile-term ops [">" "timestamp" start-time])
                 {:where   "resource_events.timestamp > ?"
                  :params  [(to-timestamp start-time)]}))
          (is (= (query/compile-term ops ["<" "timestamp" end-time])
                 {:where   "resource_events.timestamp < ?"
                  :params  [(to-timestamp end-time)]}))
          (is (= (query/compile-term ops
                                     ["and" [">=" "timestamp" start-time] ["<=" "timestamp" end-time]])
                 {:where   "(resource_events.timestamp >= ?) AND (resource_events.timestamp <= ?)"
                  :params  [(to-timestamp start-time) (to-timestamp end-time)]}))))
      (testing "should fail with invalid inequality queries"
        (is (thrown-with-msg?
             IllegalArgumentException #"> requires exactly two arguments"
             (query/compile-term ops [">" "timestamp"])))
        (is (thrown-with-msg?
             IllegalArgumentException #"'foo' is not a valid timestamp value"
             (query/compile-term ops [">" "timestamp" "foo"])))
        (is (thrown-with-msg?
             IllegalArgumentException #"> operator does not support object 'resource_type'"
             (query/compile-term ops [">" "resource_type" "foo"])))))))

(deftest resource-event-queries
  (let [basic             (store-example-report! (:basic reports) (now))
        report-hash       (:hash basic)
        basic-events      (get-in reports [:basic :resource_events])
        basic-events-map  (get-events-map (:basic reports))]
    (let [version :v4]
      (testing (str "resource event retrieval by report - version " version)
        (testing "should return the list of resource events for a given report hash"
          (let [expected  (expected-resource-events version basic-events basic)
                actual (distinct-resource-events version ["=" "report" report-hash])]
            (is (= actual expected)))))

      (testing "resource event timestamp queries"
        (testing "should return the list of resource events that occurred before a given time"
          (let [end-time  "2011-01-01T12:00:03-03:00"
                expected    (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map [1 3])
                             basic)
                actual (distinct-resource-events version ["<" "timestamp" end-time])]
            (is (= actual expected))))
        (testing "should return the list of resource events that occurred after a given time"
          (let [start-time  "2011-01-01T12:00:01-03:00"
                expected    (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map [2 3])
                             basic)
                actual (distinct-resource-events version [">" "timestamp" start-time])]
            (is (= actual expected))))
        (testing "should return the list of resource events that occurred between a given start and end time"
          (let [start-time  "2011-01-01T12:00:01-03:00"
                end-time    "2011-01-01T12:00:03-03:00"
                expected    (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map [3])
                             basic)
                actual (distinct-resource-events version ["and"
                                                          [">" "timestamp" start-time]
                                                          ["<" "timestamp" end-time]])]
            (is (= actual expected))))
        (testing "should return the list of resource events that occurred between a given start and end time (inclusive)"
          (let [start-time  "2011-01-01T12:00:01-03:00"
                end-time    "2011-01-01T12:00:03-03:00"
                expected    (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map [1 2 3])
                             basic)
                actual (distinct-resource-events version ["and"
                                                          [">=" "timestamp" start-time]
                                                          ["<=" "timestamp" end-time]])]
            (is (= actual expected)))))

      (testing "equality queries"
        (doseq [[field value matches]
                [[:resource_type    "Notify"                            [1 2 3]]
                 [:resource_title   "notify, yo"                        [1]]
                 [:status           "success"                           [1 2]]
                 [:property         "message"                           [1 2]]
                 [:property         nil                                 [3]]
                 [:old_value        ["what" "the" "woah"]               [1]]
                 [:new_value        "notify, yo"                        [1]]
                 [:message          "defined 'message' as 'notify, yo'" [1 2]]
                 [:message          nil                                 [3]]
                 [:resource_title   "bunk"                              []]
                 [:certname         "foo.local"                         [1 2 3]]
                 [:certname         "bunk.remote"                       []]
                 [:file             "foo.pp"                            [1]]
                 [:file             "bar"                               [3]]
                 [:file             nil                                 [2]]
                 [:line             2                                   [3]]
                 [:line             nil                                 [2]]
                 [:containing_class "Foo"                               [3]]
                 [:containing_class nil                                 [1 2]]]]
          (testing (format "equality query on field '%s'" field)
            (let [expected  (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["=" (name field) value]
                  actual (distinct-resource-events version query)]
              (is (= actual expected)
                  (format "Results didn't match for query '%s'" query))))))

      (testing (str "'not' queries for " version)
        (doseq [[field value matches]
                [[:resource_type    "Notify"                            []]
                 [:resource_title   "notify, yo"                        [2 3]]
                 [:status           "success"                           [3]]
                 [:property         "message"                           []]
                 [:property         nil                                 [1 2]]
                 [:old_value        ["what" "the" "woah"]               [2 3]]
                 [:new_value        "notify, yo"                        [2 3]]
                 [:message          "defined 'message' as 'notify, yo'" []]
                 [:message          nil                                 [1 2]]
                 [:resource_title   "bunk"                              [1 2 3]]
                 [:certname         "foo.local"                         []]
                 [:certname         "bunk.remote"                       [1 2 3]]
                 [:file             "foo.pp"                            [3]]
                 [:file             "bar"                               [1]]
                 [:file             nil                                 [1 3]]
                 [:line             1                                   [3]]
                 [:line             2                                   [1]]
                 [:line             nil                                 [1 3]]
                 [:containing_class "Foo"                               []]
                 [:containing_class nil                                 [3]]]]
          (testing (format "'not' query on field '%s'" field)
            (let [expected  (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["not" ["=" (name field) value]]
                  actual (distinct-resource-events version query)]
              (is (= actual expected)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "regex queries"
        (doseq [[field value matches]
                [[:resource_type    "otify"                 [1 2 3]]
                 [:resource_title   "^[Nn]otify,\\s*yo$"    [1]]
                 [:status           "^.ucces."              [1 2]]
                 [:property         "^[Mm][\\w\\s]+"        [1 2]]
                 [:message          "notify, yo"            [1 2]]
                 [:resource_title   "^bunk$"                []]
                 [:certname         "^foo\\."               [1 2 3]]
                 [:certname         "^.*\\.mydomain\\.com$" []]
                 [:file             ".*"                    [1 3]]
                 [:file             "\\.pp"                 [1]]
                 [:containing_class "[fF]oo"                [3]]]]
          (testing (format "regex query on field '%s'" field)
            (let [expected  (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["~" (name field) value]
                  actual (distinct-resource-events version query)]
              (is (= actual expected)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "negated regex queries"
        (doseq [[field value matches]
                [[:resource_type    "otify"                 []]
                 [:resource_title   "^[Nn]otify,\\s*yo$"    [2 3]]
                 [:status           "^.ucces."              [3]]
                 [:property         "^[Mm][\\w\\s]+"        [3]]
                 [:message          "notify, yo"            [3]]
                 [:resource_title   "^bunk$"                [1 2 3]]
                 [:certname         "^foo\\."               []]
                 [:certname         "^.*\\.mydomain\\.com$" [1 2 3]]
                 [:file             ".*"                    [2]]
                 [:file             "\\.pp"                 [2 3]]
                 [:containing_class "[fF]oo"                [1 2]]]]
          (testing (format "negated regex query on field '%s'" field)
            (let [expected  (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["not" ["~" (name field) value]]
                  actual (distinct-resource-events version query)]
              (is (= actual expected)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "compound queries"
        (testing "'or' equality queries"
          (doseq [[terms matches]
                  [[[[:resource_title "notify, yo"]
                     [:status         "skipped"]]       [1 3]]
                   [[[:resource_type  "bunk"]
                     [:resource_title "notify, yar"]]   [2]]
                   [[[:resource_type  "bunk"]
                     [:status         "bunk"]]          []]
                   [[[:new_value      "notify, yo"]
                     [:resource_title "notify, yar"]
                     [:resource_title "hi"]]            [1 2 3]]
                   [[[:file           "foo.pp"]
                     [:line           2]]               [1 3]]]]
            (let [expected    (expected-resource-events
                               version
                               (kitchensink/select-values basic-events-map matches)
                               basic)
                  term-fn     (fn [[field value]] ["=" (name field) value])
                  query       (vec (cons "or" (map term-fn terms)))
                  actual (distinct-resource-events version query)]
              (is (= actual expected)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "'and' equality queries"
        (doseq [[terms matches]
                [[[[:resource_type    "Notify"]
                   [:status           "success"]]     [1 2]]
                 [[[:resource_type    "bunk"]
                   [:resource_title   "notify, yar"]] []]
                 [[[:resource_title   "notify, yo"]
                   [:status           "skipped"]]     []]
                 [[[:new_value        "notify, yo"]
                   [:resource_type    "Notify"]
                   [:certname         "foo.local"]]   [1]]
                 [[[:certname         "foo.local"]
                   [:resource_type    "Notify"]]      [1 2 3]]
                 [[[:file             "foo.pp"]
                   [:line             1]]             [1]]
                 [[[:containing_class "Foo"]]         [3]]]]
          (let [expected    (expected-resource-events
                             version
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                term-fn     (fn [[field value]] ["=" (name field) value])
                query       (vec (cons "and" (map term-fn terms)))
                actual (distinct-resource-events version query)]
            (is (= actual expected)
                (format "Results didn't match for query '%s'" query)))))

      (testing "nested compound queries"
        (doseq [[query matches]
                [[["and"
                   ["or"
                    ["=" "resource_title" "hi"]
                    ["=" "resource_title" "notify, yo"]]
                   ["=" "status" "success"]]               [1]]
                 [["or"
                   ["and"
                    ["=" "resource_title" "hi"]
                    ["=" "status" "success"]]
                   ["and"
                    ["=" "resource_type" "Notify"]
                    ["=" "property" "message"]]]          [1 2]]
                 [["or"
                   ["and"
                    ["=" "file" "foo.pp"]
                    ["=" "line" 1]]
                   ["=" "line" 2]]                         [1 3]]]]
          (let [expected  (expected-resource-events
                           version
                           (kitchensink/select-values basic-events-map matches)
                           basic)
                actual (distinct-resource-events version query)]
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
                           version
                           (kitchensink/select-values basic-events-map matches)
                           basic)
                actual (distinct-resource-events version query)]
            (is (= actual expected)
                (format "Results didn't match for query '%s'" query))))))))

(deftestseq resource-event-queries-for-v4+
  [version versions]
  (let [basic             (store-example-report! (:basic reports) (now))
        basic2            (store-example-report! (:basic2 reports) (now))
        report-hash       (:hash basic)
        actual* (comp set (partial query-resource-events version))
        expected* (fn [events-map event-ids report]
                    (expected-resource-events version (kitchensink/select-values events-map event-ids) report))
        basic-events-map  (get-events-map (:basic reports))
        basic2-events-map (get-events-map (:basic2 reports))]

    (are [query event-ids] (= (actual* query)
                              (expected* basic-events-map event-ids basic))

         ["=" "configuration_version" "a81jasj123"] [1 2 3]
         ["=" "run_start_time" "2011-01-01T12:00:00-03:00"] [1 2 3]
         ["=" "run_end_time" "2011-01-01T12:10:00-03:00"] [1 2 3]
         ["=" "timestamp" "2011-01-01T12:00:01-03:00"] [1]
         ["~" "configuration_version" "a81jasj"] [1 2 3]
         ["<" "line" 2] [1]
         ["null?" "line" true] [2]
         ["or"
          ["<" "line" 2]
          ["null?" "line" true]] [1 2]
          ["<=" "line" 2] [1 3])

    (are [query basic-event-ids basic2-event-ids]
         (= (actual* query)
            (into (expected* basic-events-map basic-event-ids basic)
                  (expected* basic2-events-map basic2-event-ids basic2)))

         ["=" "containment_path" "Foo"] [3] [6]
         ["~" "containment_path" "Fo"] [3] [6]
         [">" "line" 1] [3] [4 5 6]
         [">=" "line" 1] [1 3] [4 5 6]
         ["null?" "line" false] [1 3] [4 5 6])))

(deftest latest-report-resource-event-queries
  (let [basic1        (store-example-report! (:basic reports) (now))
        report-hash1  (:hash basic1)
        events1       (get-in reports [:basic :resource_events])
        events1-map   (get-events-map (:basic reports))

        basic2        (store-example-report! (:basic2 reports) (now))
        report2-hash  (:hash basic2)
        events2       (get-in reports [:basic2 :resource_events])
        events2-map   (get-events-map (:basic2 reports))]
    (let [version :v4]
      (testing "retrieval of events for latest report only"
        (testing "applied to entire query"
          (let [expected (expected-resource-events version events2 basic2)
                actual (distinct-resource-events version ["=" "latest_report?" true])]
            (is (= actual expected))))
        (testing "applied to subquery"
          (let [expected (expected-resource-events
                            version(kitchensink/select-values events2-map [5 6]) basic2)
                actual (distinct-resource-events version ["and" ["=" "resource_type" "File"]
                                                          ["=" "latest_report?" true]])]
            (is (= actual expected)))))

      (testing (str "retrieval of events prior to latest report " version)
        (testing "applied to entire query"
          (let [expected  (expected-resource-events version events1 basic1)
                actual (distinct-resource-events version ["=" "latest_report?" false])]
            (is (= actual expected))))
        (testing "applied to subquery"
          (let [expected  (expected-resource-events
                            version (kitchensink/select-values events1-map [1 2]) basic1)
                actual (distinct-resource-events version ["and"
                                                          ["=" "status" "success"]
                                                          ["=" "latest_report?" false]])]
            (is (= actual expected)))))

      (testing "compound latest report"
        (let [results1 (expected-resource-events
                         version (kitchensink/select-values events1-map [3]) basic1)
              results2 (expected-resource-events
                         version (kitchensink/select-values events2-map [5 6]) basic2)
              expected (clojure.set/union results1 results2)
              actual (distinct-resource-events version ["or"
                                                        ["and" ["=" "status" "skipped"]
                                                         ["=" "latest_report?" false]]
                                                        ["and" ["=" "message" "created"]
                                                         ["=" "latest_report?" true]]])]
          (is (= actual expected)))))))

(deftest distinct-resource-event-queries
  (let [basic1        (store-example-report! (:basic reports) (now))
        basic3        (store-example-report! (:basic3 reports) (now))
        report-hash3  (:hash basic3)
        events1       (get-in reports [:basic :resource_events])
        events3       (get-in reports [:basic3 :resource_events])]
    (let [version :v4]
      (testing "retrieval of events for distinct resources only"
        (let [expected  (expected-resource-events version events3 basic3)
              actual (distinct-resource-events version ["=" "certname" "foo.local"] {}
                                               {:distinct_resources? true
                                                :distinct_start_time (to-timestamp 0)
                                                :distinct_end_time   (to-timestamp (now))})]
          (is (= (count events3) (count actual)))
          (is (= actual expected))))

      (testing "events should be contained within distinct resource timestamps"
        (let [expected  (expected-resource-events version events1 basic1)
              actual (distinct-resource-events version ["=" "certname" "foo.local"] {}
                                               {:distinct_resources? true
                                                :distinct_start_time (to-timestamp 0)
                                                :distinct_end_time (to-timestamp "2011-01-02T12:00:01-03:00")})]
          (is (= (count events1) (count actual)))
          (is (= actual expected))))

      (testing "filters (such as status) should be applied *after* the distinct list of most recent events has been built up"
        (let [expected  #{}
              actual (distinct-resource-events version ["and" ["=" "certname" "foo.local"]
                                                        ["=" "status" "success"]
                                                        ["=" "resource_title" "notify, yar"]]
                                               {} {:distinct_resources? true
                                                   :distinct_start_time (to-timestamp 0)
                                                   :distinct_end_time   (to-timestamp (now))})]
          (is (= (count expected) (count actual)))
          (is (= actual expected)))))))

(deftest paging-results
  (let [basic4        (store-example-report! (:basic4 reports) (now))
        events        (get-in reports [:basic4 :resource_events])
        event-count   (count events)
        select-values #(select-values' (get-events-map (:basic4 reports)) %)]
    (let [version :v4]

      (testing "limit results"
        (doseq [[limit expected] [[1 1] [2 2] [100 event-count]]]
          (let [results (query-resource-events version [">" "timestamp" 0] {:limit limit})
                actual  (count results)]
            (is (= actual expected)))))

      (testing "order_by"
        (testing "rejects invalid fields"
          (is (thrown-with-msg?
               IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order_by"
               (query-resource-events version [">" "timestamp" 0] {:order_by [[:invalid-field :ascending]]}))))

        (testing "numerical fields"
          (doseq [[order expected-events] [[:ascending  [10 11 12]]
                                           [:descending [12 11 10]]]]
            (testing order
              (let [expected (raw-expected-resource-events
                               version (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:line order]]})]
                (is (= actual expected))))))

        (testing "alphabetical fields"
          (doseq [[order expected-events] [[:ascending  [10 11 12]]
                                           [:descending [12 11 10]]]]
            (testing order
              (let [expected (raw-expected-resource-events
                               version (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:file order]]})]
                (is (= actual expected))))))

        (testing "timestamp fields"
          (doseq [[order expected-events] [[:ascending  [10 11 12]]
                                           [:descending [12 11 10]]]]
            (testing order
              (let [expected (raw-expected-resource-events
                               version (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:timestamp order]]})]
                (is (= actual expected))))))

        (testing "multiple fields"
          (doseq [[[status-order title-order] expected-events] [[[:descending :ascending] [11 10 12]]
                                                                [[:ascending :descending] [12 10 11]]]]
            (testing (format "status %s resource-title %s" status-order title-order)
              (let [expected (raw-expected-resource-events
                               version (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:status status-order]
                                                                                          [:resource_title title-order]]})]
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
              (let [expected (raw-expected-resource-events
                               version (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:line order]]
                                                                               :offset offset})]
                (is (= actual expected))))))))))

(deftest query-by-environment
  (let [basic           (store-example-report! (:basic reports) (now))
        basic2          (store-example-report! (assoc (:basic2 reports)
                                                      :environment "PROD") (now))
        basic-events    (get-in reports [:basic :resource_events])
        basic-events2    (get-in reports [:basic2 :resource_events])]
    (testing "query for DEV reports"
      (let [expected    (expected-resource-events :v4 basic-events basic)]
        (doseq [query [["=" "environment" "DEV"]
                       ["not" ["=" "environment" "PROD"]]
                       ["~" "environment" "DE.*"]
                       ["not"["~" "environment" "PR.*"]]]
                :let [actual (distinct-resource-events :v4 query {})]]
          (is (every? #(= "DEV" (:environment %)) actual))
          (is (= actual expected)))))
    (testing "query for PROD reports"
      (let [expected    (expected-resource-events :v4 basic-events2 basic2)]
        (doseq [query [["=" "environment" "PROD"]
                       ["not" ["=" "environment" "DEV"]]
                       ["~" "environment" "PR.*"]
                       ["not"["~" "environment" "DE.*"]]]
                :let [actual (distinct-resource-events :v4 query {})]]
          (is (every? #(= "PROD" (:environment %)) actual))
          (is (= actual expected)))))))
