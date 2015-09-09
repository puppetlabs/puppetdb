(ns puppetlabs.puppetdb.query-test
  (:require [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as q]
            [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :refer [excludes?]]
            [puppetlabs.puppetdb.testutils.db :refer [antonym-data with-antonym-test-database]]))

(use-fixtures :each with-antonym-test-database)

(defn execute-query
  [query paging-options]
  (:result
   (q/execute-query query paging-options)))

(deftest test-execute-query
  (testing "order by"
    (let [orig-sql "SELECT key FROM test"]
      (testing "should not modify SQL unless necessary"
        (let [validation-fn (fn [[sql & params]]
                              (is (= orig-sql sql)))]
          (with-redefs [jdbc/query-to-vec validation-fn]
            (testing "should not modify SQL if no order_by is provided"
              (execute-query orig-sql {}))
            (testing "should not modify SQL if order_by list is empty"
              (execute-query orig-sql {:order_by []})))))
      (testing "should return results in the correct order"
        (is (= (sort (keys antonym-data))
               (map #(get % :key)
                    (execute-query orig-sql
                                   {:order_by [[:key :ascending]]})))))
      (testing "should return results in correct order when :descending is specified"
        (is (= (reverse (sort (keys antonym-data)))
               (map #(get % :key)
                    (execute-query orig-sql
                                   {:order_by [[:key :descending]]}))))))
    (testing "should support multiple order_by fields"
      (is (= [{:key "blandness" :value "zest"}
              {:key "lethargy"  :value "zest"}
              {:key "abundant"  :value "scarce"}]
             (take 3
                   (execute-query "SELECT key, value from test"
                                  {:order_by [[:value :descending]
                                              [:key :ascending]]}))))))
  (testing "limit / offset"
    (let [orig-sql "SELECT key FROM test"]
      (testing "SQL not modified if no offset or limit is provided"
        (let [validation-fn (fn [[sql & params]]
                              (is (= orig-sql sql)))]
          (with-redefs [jdbc/query-to-vec validation-fn]
            (execute-query orig-sql {}))))
      (testing "Results are limited if limit is provided"
        (let [results (execute-query orig-sql
                                     {:limit 5 :order_by [[:key :ascending]]})]
          (is (= 5 (count results)))))
      (testing "Results begin at offset if offset is provided"
        (let [results     (execute-query orig-sql
                                         {:offset 2 :order_by [[:key :ascending]]})]
          (is (= "accept" (-> results first :key)))))
      (testing "Combination of limit and offset allows paging through entire result set"
        (let [orig-results        (set (jdbc/query-to-vec orig-sql))
              orig-count          (count orig-results)
              limit               5
              num-paged-queries   (java.lang.Math/ceil (/ orig-count (float limit)))
              paged-query-fn      (fn [n]
                                    (execute-query
                                     orig-sql
                                     {:limit     limit
                                      :offset    (* n limit)
                                      :order_by  [[:key :ascending]]}))
              paged-result        (->> (range num-paged-queries)
                                       (map paged-query-fn)
                                       (apply concat))]
          (is (= (count orig-results) (count paged-result)))
          (is (= orig-results (set paged-result)))))))

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
        (let [results (paged-query-fn {:limit limit :include_total false})]
          (is (= limit (count (:result results))))
          (is (excludes? results :count))))
      (testing "count should be returned if the option is true"
        (let [results (paged-query-fn {:limit limit :include_total true})]
          (is (= limit (count (:result results))))
          (is (contains? results :count))
          (is (= orig-count (:count results))))))))
