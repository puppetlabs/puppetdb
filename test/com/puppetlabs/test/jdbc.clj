(ns com.puppetlabs.test.jdbc
  (:require [com.puppetlabs.jdbc :as subject]
            [clojure.java.jdbc :as sql])
  (:use [clojure.test]
        [com.puppetlabs.puppetdb.testutils :only [clear-db-for-testing! test-db]]))

(def test-data {"absence"    "presence"
                "abundant"   "scarce"
                "accept"     "refuse"
                "accurate"   "inaccurate"
                "admit"      "deny"
                "advance"    "retreat"
                "advantage"  "disadvantage"
                "alive"      "dead"
                "always"     "never"
                "ancient"    "modern"
                "answer"     "question"
                "approval"   "disapproval"
                "arrival"    "departure"
                "artificial" "natural"
                "ascend"     "descend"
                "blandness"  "zest"
                "lethargy"   "zest"})


(defn with-test-database
  [function]
  (sql/with-connection (test-db)
    (clear-db-for-testing!))
  (subject/with-transacted-connection (test-db)
    (sql/create-table :test
                      [:key   "VARCHAR(256)" "PRIMARY KEY"]
                      [:value "VARCHAR(256)" "NOT NULL"])
    (apply (partial sql/insert-values :test [:key :value]) (map identity test-data))
    (function)))

(use-fixtures :each with-test-database)

(deftest pool-construction
  (testing "can construct pool with numeric usernames and passwords"
    (let [pool (-> (test-db)
                   (assoc :username 1234 :password 1234)
                   (subject/pooled-datasource))]
      (.close (:datasource pool)))))

(deftest query-to-vec
  (testing "query string only"
    (is (= (set (subject/query-to-vec "SELECT key FROM test WHERE key LIKE 'ab%'"))
           (set (map #(hash-map :key (name %)) [:absence :abundant])))))
  (testing "query with params"
    (doseq [[key value] test-data]
      (let [query  ["SELECT key, value FROM test WHERE key = ?" key]
            result [{:key key :value value}]]
        (is (= (subject/query-to-vec query) result)
            (str query " => " result " with vector"))
        (is (= (apply subject/query-to-vec query) result)
            (str query " => " result " with multiple params"))))))

(deftest limited-query-to-vec
  (testing "query does not exceed limit"
    (is (= (set (subject/limited-query-to-vec 100 "SELECT key FROM test WHERE key LIKE 'ab%'"))
           (set (map #(hash-map :key (name %)) [:absence :abundant])))))
  (testing "query exceeds limit"
    (is (thrown-with-msg? IllegalStateException #"more than the maximum number of results"
          (subject/limited-query-to-vec 1 "SELECT key FROM test WHERE key LIKE 'ab%'")))))

(deftest order-by->sql
  (testing "should return an empty string if no order-by is provided"
    (is (= "" (subject/order-by->sql nil))))
  (testing "should return an empty string if order-by list is empty"
    (is (= "" (subject/order-by->sql []))))
  (testing "should generate a valid SQL 'ORDER BY' clause"
    (is (= " ORDER BY foo" (subject/order-by->sql [{:field "foo"}]))))
  (testing "should support ordering in descending order"
    (is (= " ORDER BY foo DESC" (subject/order-by->sql [{:field "foo" :order "desc"}]))))
  (testing "order specifier should be case insensitive"
    (is (= " ORDER BY foo DESC" (subject/order-by->sql [{:field "foo" :order "DESC"}]))))
  (testing "should support explicitly ordering in ascending order"
    (is (= " ORDER BY foo" (subject/order-by->sql [{:field "foo" :order "ASC"}]))))
  (testing "should raise an error if order is not ASC or DESC"
    (is (thrown-with-msg? IllegalArgumentException
          #"Unsupported value .* for :order; expected one of 'DESC' or 'ASC'"
          (subject/order-by->sql [{:field "foo" :order "foo"}]))))
  (testing "should support multiple order-by fields"
    (is (= " ORDER BY foo DESC, bar"
          (subject/order-by->sql
            [{:field "foo" :order "DESC"}
             {:field "bar"}])))))

(deftest paged-query-to-vec
  (testing "order by"
    (let [orig-sql "SELECT key FROM test"]
      (testing "should not modify SQL unless necessary"
        (let [validation-fn (fn [[sql & params]]
                              (is (= orig-sql sql)))]
          (with-redefs [subject/query-to-vec validation-fn]
            (testing "should not modify SQL if no order-by is provided"
              (subject/paged-query-to-vec orig-sql {}))
            (testing "should not modify SQL if order-by list is empty"
              (subject/paged-query-to-vec orig-sql {:order-by []})))))
      (testing "should return results in the correct order"
        (is (= (sort (keys test-data))
               (map #(get % :key)
                  (subject/paged-query-to-vec orig-sql
                    {:order-by [{:field "key"}]})))))
      (testing "should return results in correct order when DESC is specified"
        (is (= (reverse (sort (keys test-data)))
               (map #(get % :key)
                (subject/paged-query-to-vec orig-sql
                  {:order-by [{:field "key" :order "DESC"}]}))))))
    (testing "should support multiple order-by fields"
      (is (= [{:key "blandness" :value "zest"}
              {:key "lethargy"  :value "zest"}
              {:key "abundant"  :value "scarce"}]
            (take 3
              (subject/paged-query-to-vec "SELECT key, value from test"
                {:order-by [{:field "value" :order "DESC"}
                            {:field "key"}]}))))))
  (testing "limit / offset"
    (let [orig-sql "SELECT key FROM test"]
      (testing "SQL not modified if no offset or limit is provided"
        (let [validation-fn (fn [[sql & params]]
                              (is (= orig-sql sql)))]
          (with-redefs [subject/query-to-vec validation-fn]
            (subject/paged-query-to-vec orig-sql {}))))
      (testing "Results are limited if limit is provided"
        (let [results (subject/paged-query-to-vec orig-sql
                        {:limit 5 :order-by [{:field "key"}]})]
          (is (= 5 (count results)))))
      (testing "Results begin at offset if offset is provided"
        (let [results     (subject/paged-query-to-vec orig-sql
                            {:offset 2 :order-by [{:field "key"}]})]
          (is (= "accept" (-> results first :key)))))
      (testing "Combination of limit and offset allows paging through entire result set"
        (let [orig-results        (set (subject/query-to-vec orig-sql))
              orig-count          (count orig-results)
              limit               5
              num-paged-queries   (java.lang.Math/ceil (/ orig-count (float limit)))
              paged-query-fn      (fn [n]
                                    (subject/paged-query-to-vec
                                      orig-sql
                                      {:limit     limit
                                       :offset    (* n limit)
                                       :order-by  [{:field "key"}]}))
              paged-result        (->> (range num-paged-queries)
                                    (map paged-query-fn)
                                    (apply concat))]
          (is (= (count orig-results) (count paged-result)))
          (is (= orig-results (set paged-result))))))))
