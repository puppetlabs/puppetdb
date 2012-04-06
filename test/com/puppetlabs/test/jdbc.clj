(ns com.puppetlabs.test.jdbc
  (:require [com.puppetlabs.jdbc :as subject]
            [clojure.java.jdbc :as sql])
  (:use [clojure.test]
        [com.puppetlabs.puppetdb.testutils :only (test-db)]))

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


(defn with-test-database
  [function]
  (subject/with-transacted-connection (test-db)
    (sql/create-table :test
                      [:key   "VARCHAR(256)" "PRIMARY KEY"]
                      [:value "VARCHAR(256)" "NOT NULL"])
    (apply (partial sql/insert-values :test [:key :value]) (map identity test-data))
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
            (str query " => " result " with multiple params"))))))
