(ns puppetlabs.puppetdb.query-eng.parse-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.query-eng.parse :as t]))

(deftest dotted-query-to-path
  (testing "vanilla dotted path"
    (is (= (parse/dotted-query->path "facts.foo.bar")
           ["facts" "foo" "bar"])))
  (testing "dot inside quotes"
    (is (= (parse/dotted-query->path "facts.\"foo.bar\".baz")
           ["facts" "\"foo.bar\"" "baz"]))
    (is (= (parse/dotted-query->path "facts.\"foo.baz.bar\".baz")
           ["facts" "\"foo.baz.bar\"" "baz"]))
    (is (= (parse/dotted-query->path "facts.\"foo.bar\".\"baz.bar\"")
           ["facts" "\"foo.bar\"" "\"baz.bar\""])))
  (testing "consecutive dots"
    (is (= (parse/dotted-query->path "facts.\"foo..bar\"")
           ["facts" "\"foo..bar\""])))
  (testing "path with quote in middle"
    (is (= (parse/dotted-query->path "facts.foo\"bar.baz")
           ["facts" "foo\"bar" "baz"])))
  (testing "path containing escaped quote"
    (is (= (parse/dotted-query->path "\"fo\\\".o\"")
           ["\"fo\\\".o\""])))
  (testing "dotted path with quote"
    (is (= (parse/dotted-query->path "facts.\"foo.bar\"baz\".biz")
           ["facts" "\"foo.bar\"baz\"" "biz"]))))

(deftest expand-array-access-in-path-test
  (are [in out] (= out (parse/expand-array-access-in-path in))
    ["a" "b[0]" "c"] ["a" "b" 0 "c"]
    ["a" "b" "c"] ["a" "b" "c"]
    ["a[0]"] ["a" 0]
    ["a[0]foo"] ["a[0]foo"]))
