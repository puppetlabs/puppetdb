(ns puppetlabs.puppetdb.query.aggregate-event-counts-test
  (:require [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!]]
            [clj-time.core :refer [now]]))

(use-fixtures :each with-test-db)

(defn- aggregate-counts-query-result
  "Utility function that executes an aggregate-event-counts query
   and returns a set of results for use in test comparison."
  ([version query summarize_by]
   (aggregate-counts-query-result version query summarize_by {}))
  ([version query summarize_by extra-query-params]
   (let [augmented-params (assoc extra-query-params :summarize_by summarize_by)]
     (-> (aggregate-event-counts/query->sql version query augmented-params)
         (aggregate-event-counts/query-aggregate-event-counts)))))

(deftest ^{:hsqldb false} aggregate-event-count-queries
  (store-example-report! (:basic reports) (now))

  (let [version :v4]
    (testing (str "summarize_by for api version" version)
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unsupported value for 'summarize_by': 'illegal-summarize-by'"
             (aggregate-counts-query-result version ["these" "are" "unused"] "illegal-summarize-by"))))

      (testing "containing_class"
        (let [expected  [{:successes 1
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 2
                          :summarize_by "containing_class"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "containing_class")]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  [{:successes 1
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 1
                          :summarize_by "certname"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "certname")]
          (is (= actual expected))))

      (testing "resource"
        (let [expected  [{:successes 2
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 3
                          :summarize_by "resource"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "resource")]
          (is (= actual expected))))

      (testing "resource,unsupported"
        (is (thrown-with-msg?
              IllegalArgumentException
              #"Unsupported value for 'summarize_by': 'unsupported'"
              (aggregate-counts-query-result
                version
                ["these" "are" "unused"]
                "resource,unsupported"))))

      (testing "resource,containing_class"
        (let [expected [{:successes 2
                         :failures 0
                         :noops 0
                         :skips 1
                         :total 3
                         :summarize_by "resource"}
                        {:successes 1
                         :failures 0
                         :noops 0
                         :skips 1
                         :total 2
                         :summarize_by "containing_class"}]
              actual (aggregate-counts-query-result version ["=" "certname" "foo.local"]
                                                    "resource,containing_class")]
          (is (= (sort-by :summarize_by actual) (sort-by :summarize_by expected))))))

    (testing "counts_filter"
      (testing "= operator"
        (let [expected  [{:successes 1
                          :failures 0
                          :noops 0
                          :skips 0
                          :total 1
                          :summarize_by "containing_class"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "containing_class" {:counts_filter ["=" "successes" 2]})]
          (is (= actual expected))))

      (testing "> operator"
        (let [expected  [{:successes 2
                          :failures 0
                          :noops 0
                          :skips 0
                          :total 2
                          :summarize_by "resource"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter [">" "successes" 0]})]
          (is (= actual expected))))

      (testing ">= operator"
        (let [expected  [{:successes 2
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 3
                          :summarize_by "resource"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter [">=" "successes" 0]})]
          (is (= actual expected))))

      (testing "< operator"
        (let [expected  [{:successes 2
                          :failures 0
                          :noops 0
                          :skips 0
                          :total 2
                          :summarize_by "resource"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter ["<" "skips" 1]})]
          (is (= actual expected))))

      (testing "<= operator"
        (let [expected  [{:successes 2
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 3
                          :summarize_by "resource"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "resource" {:counts_filter ["<=" "skips" 1]})]
          (is (= actual expected)))))

    (testing "count_by"
      (testing "rejects unsupported values"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unsupported value for 'count_by': 'illegal-count-by'"
             (aggregate-counts-query-result version ["=" "certname" "foo.local"] "certname" {:count_by "illegal-count-by"}))))

      (testing "resource"
        (let [expected  [{:successes 1
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 2
                          :summarize_by "containing_class"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "containing_class" {:count_by "resource"})]
          (is (= actual expected))))

      (testing "certname"
        (let [expected  [{:successes 1
                          :failures 0
                          :noops 0
                          :skips 1
                          :total 1
                          :summarize_by "certname"}]
              actual    (aggregate-counts-query-result version ["=" "certname" "foo.local"] "certname" {:count_by "certname"})]
          (is (= actual expected)))))

    (testing "when nothing matches, should return zeroes rather than nils"
      (let [expected  [{:successes 0
                        :failures 0
                        :noops 0
                        :skips 0
                        :total 0
                        :summarize_by "resource"}]

            actual    (aggregate-counts-query-result version ["<" "timestamp" 0] "resource")]
        (is (= actual expected))))))
