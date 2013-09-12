(ns com.puppetlabs.puppetdb.test.query.aggregate-event-counts
  (:require [com.puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        [com.puppetlabs.puppetdb.testutils.report :only [store-example-report!]]
        [clj-time.core :only [now]]))

(use-fixtures :each with-test-db)

(defn- aggregate-counts-query-result
  "Utility function that executes an aggregate-event-counts query
  and returns a set of results for use in test comparison."
  ([query summarize-by]
   (aggregate-counts-query-result query summarize-by {}))
  ([query summarize-by extra-query-params]
   (-> (aggregate-event-counts/query->sql query summarize-by extra-query-params)
       (aggregate-event-counts/query-aggregate-event-counts))))

(deftest aggregate-event-count-queries
  (store-example-report! (:basic reports) (now))

  (testing "summarize-by"
    (testing "rejects unsupported values"
      (is (thrown-with-msg?
            IllegalArgumentException #"Unsupported value for 'summarize-by': 'illegal-summarize-by'"
            (aggregate-counts-query-result ["these" "are" "unused"] "illegal-summarize-by"))))

    (testing "containing-class"
      (let [expected  {:successes 1
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 2}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "containing-class")]
        (is (= actual expected))))

    (testing "node"
      (let [expected  {:successes 1
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 1}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "node")]
        (is (= actual expected))))

    (testing "resource"
      (let [expected  {:successes 2
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 3}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "resource")]
        (is (= actual expected)))))

  (testing "counts-filter"
    (testing "= operator"
      (let [expected  {:successes 1
                       :failures 0
                       :noops 0
                       :skips 0
                       :total 1}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "containing-class" {:counts-filter ["=" "successes" 2]})]
        (is (= actual expected))))

    (testing "> operator"
      (let [expected  {:successes 2
                       :failures 0
                       :noops 0
                       :skips 0
                       :total 2}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter [">" "successes" 0]})]
        (is (= actual expected))))

    (testing ">= operator"
      (let [expected  {:successes 2
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 3}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter [">=" "successes" 0]})]
        (is (= actual expected))))

    (testing "< operator"
      (let [expected  {:successes 2
                       :failures 0
                       :noops 0
                       :skips 0
                       :total 2}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter ["<" "skips" 1]})]
        (is (= actual expected))))

    (testing "<= operator"
      (let [expected  {:successes 2
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 3}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter ["<=" "skips" 1]})]
        (is (= actual expected)))))

  (testing "count-by"
    (testing "rejects unsupported values"
      (is (thrown-with-msg?
            IllegalArgumentException #"Unsupported value for 'count-by': 'illegal-count-by'"
            (aggregate-counts-query-result ["=" "certname" "foo.local"] "node" {:count-by "illegal-count-by"}))))

    (testing "resource"
      (let [expected  {:successes 1
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 2}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "containing-class" {:count-by "resource"})]
        (is (= actual expected))))

    (testing "node"
      (let [expected  {:successes 1
                       :failures 0
                       :noops 0
                       :skips 1
                       :total 1}
            actual    (aggregate-counts-query-result ["=" "certname" "foo.local"] "node" {:count-by "node"})]
        (is (= actual expected)))))

  (testing "when nothing matches, should return zeroes rather than nils"
    (let [expected  {:successes 0
                      :failures 0
                      :noops 0
                      :skips 0
                      :total 0}

          actual    (aggregate-counts-query-result ["<" "timestamp" 0] "resource")]
      (is (= actual expected)))))
