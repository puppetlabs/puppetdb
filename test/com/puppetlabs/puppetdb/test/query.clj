(ns com.puppetlabs.puppetdb.test.query
  (:require [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as q])
  (:use [clojure.test]
        [com.puppetlabs.utils :only [excludes?]]
        [com.puppetlabs.testutils.db :only [with-antonym-test-database]]))

(use-fixtures :each with-antonym-test-database)

(deftest test-execute-query
  (testing "count"
    (let [orig-sql            "SELECT key FROM test"
          orig-results        (set (jdbc/query-to-vec orig-sql))
          orig-count          (count orig-results)
          limit               5
          paged-query-fn      (fn [paging-options]
                                (q/execute-query
                                  orig-sql
                                  paging-options))]
      (testing "count should not be returned if the option is not present"
        (let [results (paged-query-fn {:limit limit})]
          (is (= limit (count (:result results))))
          (is (excludes? results :count))))
      (testing "count should not be returned if the option is false"
        (let [results (paged-query-fn {:limit limit :count? false})]
          (is (= limit (count (:result results))))
          (is (excludes? results :count))))
      (testing "count should be returned if the option is true"
        (let [results (paged-query-fn {:limit limit :count? true})]
          (is (= limit (count (:result results))))
          (is (contains? results :count))
          (is (= orig-count (:count results))))))))