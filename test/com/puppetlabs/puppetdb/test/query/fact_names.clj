(ns com.puppetlabs.puppetdb.test.query.fact-names
  (:require [com.puppetlabs.puppetdb.query.facts :as facts]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use clojure.test
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(defn- query-fact-names
  [paging-options]
  (-> (facts/fact-names paging-options)
      (:result)))

(deftest paging-results
  (let [f1         "architecture"
        f2         "clientversion"
        f3         "domain"
        f4         "fqdn"
        fact-count 4]
    (scf-store/add-certname! "foo.local")
    (scf-store/add-facts! "foo.local" (into {} (map (fn [x] [x "unused"]) [f2 f4 f1 f3])) (now) "DEV")

    (testing "include total results count"
      (let [actual (:count (facts/fact-names {:count? true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-fact-names {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order-by"
              (query-fact-names {:order-by [[:invalid-field :ascending]]}))))

      (testing "alphabetical fields"
        (doseq [[order expected] [[:ascending  [f1 f2 f3 f4]]
                                  [:descending [f4 f3 f2 f1]]]]
          (testing order
            (let [actual (query-fact-names
                           {:order-by [[:name order]]})]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [[:ascending  [[0 [f1 f2 f3 f4]]
                                                       [1 [f2 f3 f4]]
                                                       [2 [f3 f4]]
                                                       [3 [f4]]
                                                       [4 []]]]
                                          [:descending [[0 [f4 f3 f2 f1]]
                                                       [1 [f3 f2 f1]]
                                                       [2 [f2 f1]]
                                                       [3 [f1]]
                                                       [4 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (query-fact-names
                           {:order-by [[:name order]] :offset offset})]
              (is (= actual expected)))))))))
