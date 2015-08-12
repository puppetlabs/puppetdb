(ns puppetlabs.puppetdb.query.event-counts-test
  (:require [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!]]
            [clj-time.core :refer [now]]))

(use-fixtures :each with-test-db)

(defn- query-event-counts
  [version query query-options]
  (eng/stream-query-result :event-counts
                           version
                           query
                           query-options
                           *db*
                           ""))

(def distinct-event-counts (comp set query-event-counts))

(deftest paging-results
  (let [_           (store-example-report! (:basic reports) (now))
        count1      {:subject_type "containing_class" :subject {:title nil}   :failures 0 :successes 2 :noops 0 :skips 0}
        count2      {:subject_type "containing_class" :subject {:title "Foo"} :failures 0 :successes 0 :noops 0 :skips 1}]

    (let [version :v4]

      (testing "limit results"
        (doseq [[limit expected] [[1 1] [2 2] [100 3]]]
          (let [results (distinct-event-counts version ["=" "certname" "foo.local"]
                                               {:summarize_by "resource"
                                                :limit limit})
                actual (count results)]
            (is (= actual expected)))))

      (testing "order_by"
        (testing "rejects invalid fields"
          (is (thrown-with-msg?
               IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order_by"
               (query-event-counts
                version
                ["=" "certname" "foo.local"]
                {:summarize_by "resource" :order_by [[:invalid-field :ascending]]}))))

        (testing "numerical fields"
          (doseq [[order expected] [[:ascending  [count2 count1]]
                                    [:descending [count1 count2]]]]
            (testing order
              (let [actual (query-event-counts
                            version
                            ["=" "certname" "foo.local"]
                            {:summarize_by "containing_class" :order_by [[:successes order]]})]
                (is (= actual expected)))))))

      (testing "offset"
        (doseq [[order expected-sequences] [[:ascending  [[0 [count2 count1]]
                                                          [1 [count1]]
                                                          [2 []]]]
                                            [:descending [[0 [count1 count2]]
                                                          [1 [count2]]
                                                          [2 []]]]]]
          (testing order
            (doseq [[offset expected] expected-sequences]
              (let [actual (query-event-counts
                            version
                            ["=" "certname" "foo.local"]
                            {:summarize_by "containing_class"
                             :order_by [[:successes order]] :offset offset})]
                (is (= actual expected))))))))))

(deftest resource-event-count-queries
  (store-example-report! (:basic reports) (now))

  (let [version :v4]

    (testing "summarize_by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unsupported value for 'summarize_by': 'illegal-summarize-by'"
             (query-event-counts version ["these" "are" "unused"]
                                 {:summarize_by "illegal-summarize-by"}))))

      (testing "containing_class"
        (let [expected #{{:subject_type "containing_class"
                          :subject {:title nil}
                          :failures 0
                          :successes 2
                          :noops 0
                          :skips 0}
                         {:subject_type "containing_class"
                          :subject {:title "Foo"}
                          :failures 0
                          :successes 0
                          :noops 0
                          :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "containing_class"})]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  #{{:subject_type "certname"
                           :subject {:title "foo.local"}
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "certname"})]
          (is (= actual expected))))

      (testing "resource"
        (let [expected  #{{:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yo"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yar"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "hi"}
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "resource"})]
          (is (= actual expected)))))

    (testing "counts_filter"
      (testing "= operator"
        (let [expected  #{{:subject_type "containing_class"
                           :subject {:title nil}
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 0}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "containing_class"
                                             :counts_filter ["=" "successes" 2]})]
          (is (= actual expected))))

      (testing "> operator"
        (let [expected  #{{:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yo"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yar"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "resource"
                                             :counts_filter [">" "successes" 0]})]
          (is (= actual expected))))

      (testing ">= operator"
        (let [expected  #{{:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yo"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yar"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "hi"}
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "resource"
                                             :counts_filter [">=" "successes" 0]})]
          (is (= actual expected))))

      (testing "< operator"
        (let [expected  #{{:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yo"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yar"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "resource"
                                             :counts_filter ["<" "skips" 1]})]
          (is (= actual expected))))

      (testing "<= operator"
        (let [expected  #{{:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yo"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "notify, yar"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:subject_type "resource"
                           :subject {:type "Notify" :title "hi"}
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "resource"
                                             :counts_filter ["<=" "skips" 1]})]
          (is (= actual expected)))))

    (testing "count_by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unsupported value for 'count_by': 'illegal-count-by'"
             (query-event-counts version ["=" "certname" "foo.local"]
                                 {:summarize_by "certname" :count_by "illegal-count-by"}))))

      (testing "resource"
        (let [expected  #{{:subject_type "containing_class"
                           :subject {:title nil}
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 0}
                          {:subject_type "containing_class"
                           :subject {:title "Foo"}
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "containing_class"
                                             :count_by "resource"})]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  #{{:subject_type "certname"
                           :subject {:title "foo.local"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 1}}
              actual (distinct-event-counts version ["=" "certname" "foo.local"]
                                            {:summarize_by "certname" :count_by "certname"})]
          (is (= actual expected)))))))
