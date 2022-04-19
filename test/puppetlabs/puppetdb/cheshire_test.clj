(ns puppetlabs.puppetdb.cheshire-test
  (:require
   [cheshire.factory :refer [*json-factory*]]
   [puppetlabs.puppetdb.testutils :as tu]
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.cheshire
    :refer [generate-pretty-stream
            generate-pretty-string
            generate-stream
            generate-string
            null-replacing-json-factory
            parse-stream
            parse-string
            spit-json]]
   [puppetlabs.puppetdb.time :as time])
  (:import [java.io StringWriter StringReader]
           [java.sql Timestamp]
           [org.joda.time DateTime]))

(deftest test-generate-string
  (testing "should generate a json string"
    (is (= (generate-string (sorted-map :a 1 :b 2))
           "{\"a\":1,\"b\":2}")))
  (testing "should generate a json string that has a Joda DataTime object in it and not explode"
    (is (= (generate-string (sorted-map :a 1 :b (time/date-time 1986 10 14 4 3 27 456)))
           "{\"a\":1,\"b\":\"1986-10-14T04:03:27.456Z\"}"))))

(deftest test-generate-pretty-string
  (testing "should generate a json string"
    (is (= (generate-pretty-string (sorted-map :a 1 :b 2))
           "{\n  \"a\" : 1,\n  \"b\" : 2\n}")))
  (testing "should generate a json string that has a Joda DataTime object in it and not explode"
    (is (= (generate-pretty-string (sorted-map :a 1 :b (time/date-time 1986 10 14 4 3 27 456)))
           "{\n  \"a\" : 1,\n  \"b\" : \"1986-10-14T04:03:27.456Z\"\n}"))))

(deftest test-generate-stream
  (testing "should generate a json string from a stream"
    (let [sw (StringWriter.)]
      (generate-stream (sorted-map :a 1 :b 2) sw)
      (is (= (.toString sw)
             "{\"a\":1,\"b\":2}")))))

(deftest test-generate-pretty-stream
  (testing "should generate a pretty printed json string from a stream"
    (let [sw (StringWriter.)]
      (generate-pretty-stream (sorted-map :a 1 :b 2) sw)
      (is (= (.toString sw)
             "{\n  \"a\" : 1,\n  \"b\" : 2\n}")))))

(deftest test-date-generation
  (let [t (java.util.Date. 5928174905781)]
    (is (= "\"2157-11-09T03:15:05.781Z\""
           (generate-string t))))
  (let [t (DateTime. 5928174905781)]
    (is (= "\"2157-11-09T03:15:05.781Z\""
           (generate-string t))))
  (let [t (Timestamp. 5928174905781)]
    (is (= "\"2157-11-09T03:15:05.781Z\""
           (generate-string t)))))

(deftest test-parse-string
  (testing "should return a map from parsing a json string"
    (is (= (parse-string "{\"a\":1,\"b\":2}")
           {"a" 1 "b" 2}))))

(deftest test-parse-stream
  (testing "should return  map from parsing a json stream"
    (is (= (parse-stream (StringReader. "{\"a\":1,\"b\":2}"))
           {"a" 1 "b" 2}))))

(deftest test-spit-json
  (let [json-out (tu/temp-file "spit-json")]
    (testing "json output with keywords"
      (spit-json json-out (sorted-map :a 1 :b 2))
      (is (= "{\n  \"a\" : 1,\n  \"b\" : 2\n}"
             (slurp json-out))))
    (testing "json output with strings"
      (spit-json json-out (sorted-map "a" 1 "b" 2))
      (is (= "{\n  \"a\" : 1,\n  \"b\" : 2\n}"
             (slurp json-out))))))

(deftest test-null-replacing-encoder
  (binding [*json-factory* null-replacing-json-factory]
    (are [json src] (= json (generate-string src))
         "\"\\ufffd\"" "\u0000"
         "\"\\\\u0000\"" "\\u0000"
         "[\"\\ufffd\"]" ["\u0000"])))
