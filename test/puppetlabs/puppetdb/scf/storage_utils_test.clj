(ns puppetlabs.puppetdb.scf.storage-utils-test
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage-utils :refer :all]
            [puppetlabs.puppetdb.testutils.db :refer [with-test-db]]
            [puppetlabs.puppetdb.jdbc :as jdbc]))

(deftest serialization
  (let [values ["foo" 0 "0" nil "nil" "null" [1 2 3] ["1" "2" "3"] {"a" 1 "b" [1 2 3]}]]
    (testing "serialized values should deserialize to the initial value"
      (doseq [value values]
        (is (= (json/parse-string (db-serialize value)) value))))
    (testing "serialized values should be unique"
      (doseq [value1 values
              value2 values]
        (let [str1 (db-serialize value1)
              str2 (db-serialize value2)]
          (when (= value1 value2)
            (is (= str1 str2)))
          (when-not (= value1 value2)
            (is (not= str1 str2)
                (str value1 " should not serialize the same as " value2)))))))
  (let [values ["foo" 0 {"z" 1 "a" 1}]
        expected ["foo" 0 {"a" 1 "z" 1}]]
    (testing "should sort beforehand"
      (is (= (json/parse-string (db-serialize values)) expected))))
  (let [sample {:b "asdf" :a {:z "asdf" :k [:z {:z 26 :a 1} :c] :a {:m "asdf" :b "asdf"}}}]
    (testing "serialized value should be sorted and predictable"
      (is (= (db-serialize sample)
             "{\"a\":{\"a\":{\"b\":\"asdf\",\"m\":\"asdf\"},\"k\":[\"z\",{\"a\":1,\"z\":26},\"c\"],\"z\":\"asdf\"},\"b\":\"asdf\"}")))))

(deftest test-pg-extension?
  (testing "check if plpsql is installed"
    (with-test-db
      (is (true? (pg-extension? "plpgsql"))))))

(deftest test-index-exists?
  (with-test-db
    (testing "test to see if an index doesn't exists"
      (is (false? (index-exists? "somerandomname"))))
    (testing "test to see if an index does exist"
      (jdbc/do-commands "CREATE INDEX foobar ON catalog_resources(line)")
      (is (true? (index-exists? "foobar"))))))

(deftest dotted-query-to-path
  (testing "vanilla dotted path"
    (is (= (dotted-query->path "facts.foo.bar")
           ["facts" "foo" "bar"])))
  (testing "dot inside quotes"
    (is (= (dotted-query->path "facts.\"foo.bar\".baz")
           ["facts" "\"foo.bar\"" "baz"]))
    (is (= (dotted-query->path "facts.\"foo.baz.bar\".baz")
           ["facts" "\"foo.baz.bar\"" "baz"]))
    (is (= (dotted-query->path "facts.\"foo.bar\".\"baz.bar\"")
           ["facts" "\"foo.bar\"" "\"baz.bar\""])))
  (testing "consecutive dots"
    (is (= (dotted-query->path "facts.\"foo..bar\"")
           ["facts" "\"foo..bar\""])))
  (testing "path with quote in middle"
    (is (= (dotted-query->path "facts.foo\"bar.baz")
           ["facts" "foo\"bar" "baz"])))
  (testing "path containing escaped quote"
    (is (= (dotted-query->path "\"fo\\\".o\"")
           ["\"fo\\\".o\""])))
  (testing "dotted path with quote"
    (is (= (dotted-query->path "facts.\"foo.bar\"baz\".biz")
           ["facts" "\"foo.bar\"baz\"" "biz"]))))

(deftest expand-array-access-in-path-test
  (are [in out] (= out (expand-array-access-in-path in))
    ["a" "b[0]" "c"] ["a" "b" 0 "c"]
    ["a" "b" "c"] ["a" "b" "c"]
    ["a[0]"] ["a" 0]
    ["a[0]foo"] ["a[0]foo"]))

(deftest json-adjustments-for-pg
  (are [db-value src] (= db-value (-> src munge-jsonb-for-storage .getValue))
       "\"\\ufffd\"" "\u0000"
       "\"\\\\u0000\"" "\\u0000"
       "[\"\\ufffd\"]" ["\u0000"]))
