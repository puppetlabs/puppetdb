(ns com.puppetlabs.test.jdbc
  (:require [com.puppetlabs.jdbc :as subject]
            [clojure.java.jdbc :as sql])
  (:use [clojure.test]
        [com.puppetlabs.puppetdb.testutils :only [test-db]]
        [com.puppetlabs.testutils.db :only [antonym-data with-antonym-test-database]]))


(use-fixtures :each with-antonym-test-database)

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
    (doseq [[key value] antonym-data]
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
    (is (= " ORDER BY foo" (subject/order-by->sql [[:foo :ascending]]))))
  (testing "should support ordering in descending order"
    (is (= " ORDER BY foo DESC" (subject/order-by->sql [[:foo :descending]]))))
  (testing "should support multiple order-by fields"
    (is (= " ORDER BY foo DESC, bar"
          (subject/order-by->sql
            [[:foo :descending]
             [:bar :ascending]])))))
