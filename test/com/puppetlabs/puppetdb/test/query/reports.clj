(ns com.puppetlabs.puppetdb.test.query.reports
  (:require [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.fixtures :refer :all]
            [com.puppetlabs.puppetdb.testutils.reports :refer :all]
            [com.puppetlabs.time :refer [to-secs]]
            [clj-time.core :refer [now ago days]]))

(use-fixtures :each with-test-db)

(def my-reports
  (-> reports
      (assoc-in [:basic  :report-format] 3)
      (assoc-in [:basic2 :report-format] 4)
      (assoc-in [:basic3 :report-format] 6)
      (assoc-in [:basic4 :report-format] 5)
      (assoc-in [:basic  :transaction-uuid] "aaa-111")
      (assoc-in [:basic2 :transaction-uuid] "bbb-222")
      (assoc-in [:basic3 :transaction-uuid] "ccc-444")
      (assoc-in [:basic4 :transaction-uuid] "ccc-333")
      (assoc-in [:basic  :start-time] (-> 1 days ago))
      (assoc-in [:basic2 :start-time] (-> 4 days ago))
      (assoc-in [:basic3 :start-time] (-> 3 days ago))
      (assoc-in [:basic4 :start-time] (-> 2 days ago))
      (assoc-in [:basic  :puppet-version] "3.0.1")
      (assoc-in [:basic2 :puppet-version] "3.0.1")
      (assoc-in [:basic3 :puppet-version] "4.1.0")
      (assoc-in [:basic4 :puppet-version] "4.1.0")
      (assoc-in [:basic  :configuration-version] "bbb")
      (assoc-in [:basic2 :configuration-version] "aaa")
      (assoc-in [:basic3 :configuration-version] "xxx")
      (assoc-in [:basic4 :configuration-version] "yyy")))

;; Begin tests

(deftest test-compile-report-term
  (testing "should successfully compile a valid equality query"
    (is (= ((query/compile-reports-equality :v4) "certname" "foo.local")
           {:where   "reports.certname = ?"
            :params  ["foo.local"]})))
  (testing "should fail with an invalid equality query"
    (is (thrown-with-msg?
         IllegalArgumentException #"is not a valid query term"
         ((query/compile-reports-equality :v4) "foo" "foo")))))

(deftest reports-retrieval
  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))]
    (testing "should return reports based on certname"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result :v4 ["=" "certname" (:certname basic)])]
        (is (= expected actual))))

    (testing "should return reports based on hash"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result :v4 ["=" "hash" report-hash])]
        (is (= expected actual))))))

(deftest paging-results
  (let [hash1        (:hash (store-example-report! (:basic  my-reports) (now)))
        hash2        (:hash (store-example-report! (:basic2 my-reports) (now)))
        hash3        (:hash (store-example-report! (:basic3 my-reports) (now)))
        hash4        (:hash (store-example-report! (:basic4 my-reports) (now)))
        report1      (assoc (:basic  my-reports) :hash hash1)
        report2      (assoc (:basic2 my-reports) :hash hash2)
        report3      (assoc (:basic3 my-reports) :hash hash3)
        report4      (assoc (:basic4 my-reports) :hash hash4)
        report-count 4]

    (testing "include total results count"
      (let [actual (:count (raw-reports-query-result :v4 ["=" "certname" "foo.local"] {:count? true}))]
        (is (= actual report-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 report-count]]]
        (let [results (reports-query-result :v4 ["=" "certname" "foo.local"] {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order-by"
             (reports-query-result :v4 ["=" "certname" "foo.local"] {:order-by [[:invalid-field :ascending]]}))))

      (testing "numerical fields"
        (doseq [[order expecteds] [[:ascending  [report1 report2 report4 report3]]
                                   [:descending [report3 report4 report2 report1]]]]
          (testing order
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order-by [[:report-format order]]})]
              (is (= actual expected))))))

      (testing "alphabetical fields"
        (doseq [[order expecteds] [[:ascending  [report1 report2 report4 report3]]
                                   [:descending [report3 report4 report2 report1]]]]
          (testing order
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order-by [[:transaction-uuid order]]})]
              (is (= actual expected))))))

      (testing "timestamp fields"
        (doseq [[order expecteds] [[:ascending  [report2 report3 report4 report1]]
                                   [:descending [report1 report4 report3 report2]]]]
          (testing order
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order-by [[:start-time order]]})]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[puppet-version-order conf-version-order] expecteds] [[[:ascending :descending] [report1 report2 report4 report3]]
                                                                       [[:descending :ascending] [report3 report4 report2 report1]]]]
          (testing (format "puppet-version %s configuration-version %s" puppet-version-order conf-version-order)
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order-by [[:puppet-version puppet-version-order]
                                                             [:configuration-version conf-version-order]]})]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [[:ascending  [[0 [report1 report2 report4 report3]]
                                                        [1 [report2 report4 report3]]
                                                        [2 [report4 report3]]
                                                        [3 [report3]]
                                                        [4 []]]]
                                          [:descending [[0 [report3 report4 report2 report1]]
                                                        [1 [report4 report2 report1]]
                                                        [2 [report2 report1]]
                                                        [3 [report1]]
                                                        [4 []]]]]]
        (testing order
          (doseq [[offset expecteds] expected-sequences]
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order-by [[:report-format order]] :offset offset})]
              (is (= actual expected)))))))))
