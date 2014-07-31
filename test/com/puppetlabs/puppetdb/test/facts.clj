(ns com.puppetlabs.puppetdb.test.facts
  (:require [com.puppetlabs.puppetdb.facts :refer :all]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.test :refer :all]))

(def current-time (to-timestamp (now)))

(deftest test-flatten-fact-value
  (testing "check basic types work"
    (is (= (flatten-fact-value "foo") "foo"))
    (is (= (flatten-fact-value 3) "3"))
    (is (= (flatten-fact-value true) "true"))
    (is (= (flatten-fact-value {:a :b}) "{\"a\":\"b\"}"))
    (is (= (flatten-fact-value [:a :b]) "[\"a\",\"b\"]"))))

(deftest test-flatten-fact-set
  (testing "ensure we get back a flattened set of values"
    (is (= (flatten-fact-set {"networking"
                              {"eth0"
                               {"ipaddresses" ["192.168.1.1"]}}})
           {"networking" "{\"eth0\":{\"ipaddresses\":[\"192.168.1.1\"]}}"}))))

(deftest test-factmap-to-paths
  (testing "should convert a conventional factmap to a set of paths"
    (is (= (sort-by :value_hash (factmap-to-paths {"networking"
                                                   {"eth0"
                                                    {"ipaddresses" ["1.1.1.1", "2.2.2.2"]}}
                                                   "os"
                                                   {"operatingsystem" "Linux"}
                                                   "avgload" 5.64
                                                   "empty_hash" {}
                                                   "empty_array" []}))
           [{:path "os#~operatingsystem"
             :depth 1
             :value_type_id 0
             :value_hash "a5bc91f0e5033e61ed90ff6621fb0bf1c8355f64"
             :value_string "Linux"
             :value_integer nil
             :value_float nil
             :value_boolean nil}
            {:path "networking#~eth0#~ipaddresses#~1"
             :depth 3
             :value_type_id 0
             :value_hash "c1a1b4decce49801f7f41873282b1650aef5137d"
             :value_string "2.2.2.2"
             :value_integer nil
             :value_float nil
             :value_boolean nil}
            {:path "avgload"
             :depth 0
             :value_type_id 2
             :value_hash "ee5b587330bf5e2f31eade331c1ec2a1213b7457"
             :value_string nil
             :value_integer nil
             :value_float 5.64
             :value_boolean nil}
            {:path "networking#~eth0#~ipaddresses#~0"
             :depth 3
             :value_type_id 0
             :value_hash "fcdd2924e5804c69ee520dcbd31b717b81ed66c5"
             :value_string "1.1.1.1"
             :value_integer nil
             :value_float nil
             :value_boolean nil}]))))

(deftest test-str->num
  (are [n s] (= n (str->num s))

       10 "10"
       123 "123"
       0 "0"
       nil "foo"
       nil "123foo"))

(deftest test-int-map->vector
  (are [v m] (= v (int-map->vector m))

       ["foo" "bar" "baz"] {1 "bar" 0 "foo" 2 "baz"}
       nil {"1" "bar" "0" "foo" "2" "baz"}
       nil {1 "bar" "0" "foo" "2" "baz"} ;;shouldn't be possible
       ))

(deftest test-unescape-string
  (are [unescaped s] (= unescaped (unescape-string s))

       "foo" "\"foo\""
       "foo" "foo"
       "123" "123"
       "123foo" "\"123foo\""))

(deftest test-unencode-path-segment
  (are [path-segment s] (= path-segment (unencode-path-segment s))

       "foo" "\"foo\""
       "\"foo\"" "\"\"foo\"\""
       "foo" "foo"

       "123" "\"123\""
       "123foo" "\"123foo\""
       1 "1"
       123 "123"))

(deftest test-factpath-to-string-and-reverse
  (let [data
        [["foo#~bar"      ["foo" "bar"]]
         ["foo"           ["foo"]]
         ["foo#~bar#~baz" ["foo" "bar" "baz"]]
         ["foo\\#\\~baz"  ["foo#~baz"]]
         ["foo#~0"        ["foo" 0]]
         ["foo#~\"123\""  ["foo" "123"]]]]
    (doseq [[r l] data]
      (is (= (string-to-factpath r) l))
      (is (= r (factpath-to-string l))))))
