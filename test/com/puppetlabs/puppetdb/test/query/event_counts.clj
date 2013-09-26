(ns com.puppetlabs.puppetdb.test.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.event-counts :as event-counts])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.reports
        [com.puppetlabs.puppetdb.testutils.reports :only [store-example-report!]]
        [clj-time.core :only [now]]))

(use-fixtures :each with-test-db)

(defn- event-counts-query-result
  "Utility function that executes an event-counts query and
  returns a set of results for use in test comparison."
  ([query summarize-by]
    (event-counts-query-result query summarize-by {}))
  ([query summarize-by extra-query-params]
    (-> (event-counts/query->sql query summarize-by extra-query-params)
        (event-counts/query-event-counts summarize-by)
        (:result)
        (set))))

(deftest resource-event-count-queries
  (store-example-report! (:basic reports) (now))

  (testing "summarize-by"
    (testing "rejects unsupported values"
      (is (thrown-with-msg?
            IllegalArgumentException #"Unsupported value for 'summarize-by': 'illegal-summarize-by'"
            (event-counts-query-result ["these" "are" "unused"] "illegal-summarize-by"))))

    (testing "containing-class"
      (let [expected #{{:subject-type "containing-class"
                        :subject {:title nil}
                        :failures 0
                        :successes 2
                        :noops 0
                        :skips 0}
                       {:subject-type "containing-class"
                        :subject {:title "Foo"}
                        :failures 0
                        :successes 0
                        :noops 0
                        :skips 1}}
            actual   (event-counts-query-result ["=" "certname" "foo.local"] "containing-class")]
        (is (= actual expected))))

    (testing "certname"
      (let [expected  #{{:subject-type "certname"
                         :subject {:title "foo.local"}
                         :failures 0
                         :successes 2
                         :noops 0
                         :skips 1}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "certname")]
        (is (= actual expected))))

    (testing "resource"
      (let [expected  #{{:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yo"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yar"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "hi"}
                         :failures 0
                         :successes 0
                         :noops 0
                         :skips 1}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource")]
        (is (= actual expected)))))

  (testing "counts-filter"
    (testing "= operator"
      (let [expected  #{{:subject-type "containing-class"
                         :subject {:title nil}
                         :failures 0
                         :successes 2
                         :noops 0
                         :skips 0}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "containing-class" {:counts-filter ["=" "successes" 2]})]
        (is (= actual expected))))

    (testing "> operator"
      (let [expected  #{{:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yo"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yar"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter [">" "successes" 0]})]
        (is (= actual expected))))

    (testing ">= operator"
      (let [expected  #{{:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yo"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yar"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "hi"}
                         :failures 0
                         :successes 0
                         :noops 0
                         :skips 1}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter [">=" "successes" 0]})]
        (is (= actual expected))))

    (testing "< operator"
      (let [expected  #{{:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yo"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yar"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter ["<" "skips" 1]})]
        (is (= actual expected))))

    (testing "<= operator"
      (let [expected  #{{:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yo"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yar"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "hi"}
                         :failures 0
                         :successes 0
                         :noops 0
                         :skips 1}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "resource" {:counts-filter ["<=" "skips" 1]})]
        (is (= actual expected)))))

  (testing "count-by"
    (testing "rejects unsupported values"
      (is (thrown-with-msg?
            IllegalArgumentException #"Unsupported value for 'count-by': 'illegal-count-by'"
            (event-counts-query-result ["=" "certname" "foo.local"] "certname" {:count-by "illegal-count-by"}))))

    (testing "resource"
      (let [expected  #{{:subject-type "containing-class"
                         :subject {:title nil}
                         :failures 0
                         :successes 2
                         :noops 0
                         :skips 0}
                        {:subject-type "containing-class"
                         :subject {:title "Foo"}
                         :failures 0
                         :successes 0
                         :noops 0
                         :skips 1}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "containing-class" {:count-by "resource"})]
        (is (= actual expected))))

    (testing "certname"
      (let [expected  #{{:subject-type "certname"
                         :subject {:title "foo.local"}
                         :failures 0
                         :successes 1
                         :noops 0
                         :skips 1}}
            actual    (event-counts-query-result ["=" "certname" "foo.local"] "certname" {:count-by "certname"})]
        (is (= actual expected))))))
