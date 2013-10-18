(ns com.puppetlabs.test.cheshire
  (:require [clj-time.core :as clj-time])
  (:import (java.io StringWriter StringReader))
  (:use clojure.test
        com.puppetlabs.cheshire))

(deftest test-generate-string
  (testing "should generate a json string"
    (is (= (generate-string {:a 1 :b 2})
            "{\"a\":1,\"b\":2}")))
  (testing "should generate a json string that has a Joda DataTime object in it and not explode"
    (is (= (generate-string {:a 1 :b (clj-time/date-time 1986 10 14 4 3 27 456)})
            "{\"a\":1,\"b\":\"1986-10-14T04:03:27.456Z\"}"))))

(deftest test-generate-stream
  (testing "should generate a json string from a stream"
    (let [sw (StringWriter.)]
      (generate-stream {:a 1 :b 2} sw)
      (is (= (.toString sw)
              "{\"a\":1,\"b\":2}")))))

(deftest test-parse-string
  (testing "should return a map from parsing a json string"
    (is (= (parse-string "{\"a\":1,\"b\":2}")
            {"a" 1 "b" 2}))))

(deftest test-parse-stream
  (testing "should return  map from parsing a json stream"
    (is (= (parse-stream (StringReader. "{\"a\":1,\"b\":2}"))
            {"a" 1 "b" 2}))))
