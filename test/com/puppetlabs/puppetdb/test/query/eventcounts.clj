(ns com.puppetlabs.puppetdb.test.query.eventcounts
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        [com.puppetlabs.puppetdb.testutils.report :only [store-example-report! get-events-map]]
        com.puppetlabs.puppetdb.testutils.eventcounts
        [clj-time.core :only [now]]))

(use-fixtures :each with-test-db)

(deftest resource-event-count-queries
  (let [_ (store-example-report! (:basic reports) (now))]

    (testing "summarize-by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unsupported value for 'summarize-by': 'illegal-summarize-by'"
              (event-counts-query-result ["these" "are" "unused"] "illegal-summarize-by" nil nil))))

      (testing "containing-class"
        (let [expected #{{:containing_class nil
                          :failures 0
                          :successes 2
                          :noops 0
                          :skips 0}
                         {:containing_class "Foo"
                          :failures 0
                          :successes 0
                          :noops 0
                          :skips 1}}
              actual   (event-counts-query-result ["=" "certname" "foo.local"] "containing-class" nil nil)]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  #{{:certname "foo.local"
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "certname" nil nil)]
          (is (= actual expected))))

      (testing "resource"
        (let [expected  #{{:resource_type "Notify"
                           :resource_title "notify, yo"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "notify, yar"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "hi"
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" nil nil)]
          (is (= actual expected)))))

    (testing "counts-filter"
      (testing "= operator"
        (let [expected  #{{:containing_class nil
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 0}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "containing-class" ["=" "successes" 2] nil)]
          (is (= actual expected))))

      (testing "> operator"
        (let [expected  #{{:resource_type "Notify"
                           :resource_title "notify, yo"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "notify, yar"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" [">" "successes" 0] nil)]
          (is (= actual expected))))

      (testing ">= operator"
        (let [expected  #{{:resource_type "Notify"
                           :resource_title "notify, yo"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "notify, yar"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "hi"
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" [">=" "successes" 0] nil)]
          (is (= actual expected))))

      (testing "< operator"
        (let [expected  #{{:resource_type "Notify"
                           :resource_title "notify, yo"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "notify, yar"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" ["<" "skips" 1] nil)]
          (is (= actual expected))))

      (testing "<= operator"
        (let [expected  #{{:resource_type "Notify"
                           :resource_title "notify, yo"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "notify, yar"
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:resource_type "Notify"
                           :resource_title "hi"
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" ["<=" "skips" 1] nil)]
          (is (= actual expected)))))

    (testing "count-by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unsupported value for 'count-by': 'illegal-count-by'"
              (event-counts-query-result ["=" "certname" "foo.local"] "certname" nil "illegal-count-by"))))

      (testing "resource"
        (let [expected  #{{:containing_class nil
                           :failures 0
                           :successes 2
                           :noops 0
                           :skips 0}
                          {:containing_class "Foo"
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "containing-class" nil "resource")]
          (is (= actual expected))))

      (testing "node"
        (let [expected  #{{:containing_class nil
                           :failures 0
                           :successes 1
                           :noops 0
                           :skips 0}
                          {:containing_class "Foo"
                           :failures 0
                           :successes 0
                           :noops 0
                           :skips 1}}
              actual    (event-counts-query-result ["=" "certname" "foo.local"] "containing-class" nil "node")]
          (is (= actual expected)))))))
