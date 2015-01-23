(ns puppetlabs.puppetdb.query.event-counts-test
  (:require [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!]]
            [clj-time.core :refer [now]]))

(use-fixtures :each with-test-db)

(defn- raw-event-counts-query-result
  [version query summarize_by query-options paging-options]
  (->> (event-counts/query->sql version query [summarize_by query-options paging-options])
       (event-counts/query-event-counts version summarize_by)))

(defn- event-counts-query-result
  "Utility function that executes an event-counts query and
  returns a set of results for use in test comparison."
  ([version query summarize_by]
     (event-counts-query-result version query summarize_by {}))
  ([version query summarize_by query-options]
     (event-counts-query-result version query summarize_by query-options {}))
  ([version query summarize_by query-options paging-options]
     (-> (raw-event-counts-query-result version query summarize_by query-options paging-options)
         (:result)
         (set))))

(deftest paging-results
  (let [_           (store-example-report! (:basic reports) (now))
        count1      {:subject_type "containing_class" :subject {:title nil}   :failures 0 :successes 2 :noops 0 :skips 0}
        count2      {:subject_type "containing_class" :subject {:title "Foo"} :failures 0 :successes 0 :noops 0 :skips 1}]

    (let [version :v4]

      (testing "include total results count"
        (let [actual (:count (raw-event-counts-query-result version ["=" "certname" "foo.local"] "resource" {} {:count? true}))]
          (is (= actual 3))))

      (testing "limit results"
        (doseq [[limit expected] [[1 1] [2 2] [100 3]]]
          (let [results (event-counts-query-result version ["=" "certname" "foo.local"] "resource" {} {:limit limit})
                actual  (count results)]
            (is (= actual expected)))))

      (testing "order_by"
        (testing "rejects invalid fields"
          (is (thrown-with-msg?
               IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order_by"
               (event-counts-query-result
                version
                ["=" "certname" "foo.local"]
                "resource"
                {}
                {:order_by [[:invalid-field :ascending]]}))))

        (testing "numerical fields"
          (doseq [[order expected] [[:ascending  [count2 count1]]
                                    [:descending [count1 count2]]]]
            (testing order
              (let [actual (:result (raw-event-counts-query-result
                                     version
                                     ["=" "certname" "foo.local"]
                                     "containing_class"
                                     {}
                                     {:order_by [[:successes order]]}))]
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
              (let [actual (:result (raw-event-counts-query-result
                                     version
                                     ["=" "certname" "foo.local"]
                                     "containing_class"
                                     {}
                                     {:order_by [[:successes order]] :offset offset}))]
                (is (= actual expected))))))))))

(deftest resource-event-count-queries
  (store-example-report! (:basic reports) (now))

  (let [version :v4]

    (testing "summarize_by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unsupported value for 'summarize_by': 'illegal-summarize-by'"
             (event-counts-query-result version ["these" "are" "unused"] "illegal-summarize-by"))))

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
              actual   (event-counts-query-result version ["=" "certname" "foo.local"] "containing_class")]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  #{{:subject_type "certname"
                           :subject {:title "foo.local"}
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "certname")]
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
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "resource")]
          (is (= actual expected)))))

    (testing "counts_filter"
      (testing "= operator"
        (let [expected  #{{:subject_type "containing_class"
                           :subject {:title nil}
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 0}}
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "containing_class" {:counts_filter ["=" "successes" 2]})]
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
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter [">" "successes" 0]})]
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
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter [">=" "successes" 0]})]
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
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter ["<" "skips" 1]})]
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
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter ["<=" "skips" 1]})]
          (is (= actual expected)))))

    (testing "count_by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unsupported value for 'count_by': 'illegal-count-by'"
             (event-counts-query-result version ["=" "certname" "foo.local"] "certname" {:count_by "illegal-count-by"}))))

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
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "containing_class" {:count_by "resource"})]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  #{{:subject_type "certname"
                           :subject {:title "foo.local"}
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result version ["=" "certname" "foo.local"] "certname" {:count_by "certname"})]
          (is (= actual expected)))))))
