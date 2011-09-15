(ns com.puppetlabs.test.jdbc
  (:require [com.puppetlabs.jdbc :as subject]
            [clojure.java.jdbc :as sql])
  (:use [clojure.test]
        [com.puppetlabs.cmdb.scf.storage :only [sql-array-type-string
                                                to-jdbc-varchar-array]]))

(def *db* {:classname   "org.h2.Driver"
           :subprotocol "h2"
           :subname     "mem:com-puppetlabs-test-jdbc"})

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
                "ascend"     "descend"})

(def array-test-data {"answer"     ["question" "query"]
                      "approached" ["receded" "departed"]
                      "advance"    ["retreat" "retire"]})



(defn with-test-database
  [function]
  (sql/with-connection *db*
    (sql/create-table :test
                      [:key   "VARCHAR" "PRIMARY KEY"]
                      [:value "VARCHAR" "NOT NULL"])
    (apply (partial sql/insert-values :test [:key :value]) (map identity test-data))
    (sql/create-table :arraytest
                      [:key   "VARCHAR" "PRIMARY KEY"]
                      [:value (sql-array-type-string "VARCHAR") "NOT NULL"])
    (apply (partial sql/insert-values :arraytest [:key :value])
           (map #(vector (first %1) (to-jdbc-varchar-array (second %1))) array-test-data))
    (function)))

(use-fixtures :each with-test-database)


(deftest query-to-vec
  (testing "query string only"
    (is (= (subject/query-to-vec "SELECT key FROM test WHERE key LIKE 'ab%'")
           (map #(hash-map :key %) ["absence" "abundant"]))))
  (testing "query with params"
    (doseq [[key value] test-data]
      (let [query  ["SELECT key, value FROM test WHERE key = ?" key]
            result [{:key key :value value}]]
        (is (= (subject/query-to-vec query) result)
            (str query " => " result " with vector"))
        (is (= (apply subject/query-to-vec query) result)
            (str query " => " result " with multiple params")))))
  (testing "array columns"
    (doseq [[key value] array-test-data]
      (let [query  ["SELECT key, value FROM arraytest WHERE key = ?" key]
            result [{:key key :value (vec value)}]]
        (is (= (subject/query-to-vec query) result)
            (str query " => " result " with vector"))
        (is (= (apply subject/query-to-vec query) result)
            (str query " => " result " with multiple params"))))))