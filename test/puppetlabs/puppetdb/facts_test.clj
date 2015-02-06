(ns puppetlabs.puppetdb.facts-test
  (:require [puppetlabs.puppetdb.facts :refer :all]
            [clojure.test :refer :all]))

(deftest test-factmap-to-paths
  (testing "should convert a conventional factmap to a set of paths"
    (is (= (sort-by :value-hash
                    (factmap-to-paths {"networking"
                                       {"eth0"
                                        {"ipaddresses" ["1.1.1.1", "2.2.2.2"]}}
                                       "os"
                                       {"operatingsystem" "Linux"}
                                       "avgload" 5.64
                                       "empty_hash" {}
                                       "empty_array" []}))
           [{:path "os",
             :name "os",
             :value-json "{\"operatingsystem\":\"Linux\"}",
             :value-type-id 5,
             :value-float nil,
             :value-boolean nil,
             :depth 0,
             :value-string nil,
             :value-hash "921e7f69959e0387e39552ac19cfaaf54a05b2d9",
             :value-integer nil}
            {:path "os#~operatingsystem",
             :name "os",
             :value-json nil,
             :value-type-id 0,
             :value-float nil,
             :value-boolean nil,
             :depth 1,
             :value-string "Linux",
             :value-hash "a5bc91f0e5033e61ed90ff6621fb0bf1c8355f64",
             :value-integer nil}
            {:path "networking",
             :name "networking",
             :value-json "{\"eth0\":{\"ipaddresses\":[\"1.1.1.1\",\"2.2.2.2\"]}}",
             :value-type-id 5,
             :value-float nil,
             :value-boolean nil,
             :depth 0,
             :value-string nil,
             :value-hash "abe89fbed661f1f0cc37f60d1061763db60a4523",
             :value-integer nil}
            {:path "networking#~eth0#~ipaddresses#~1",
             :name "networking",
             :value-json nil,
             :value-type-id 0,
             :value-float nil,
             :value-boolean nil,
             :depth 3,
             :value-string "2.2.2.2",
             :value-hash "c1a1b4decce49801f7f41873282b1650aef5137d",
             :value-integer nil}
            {:path "avgload",
             :name "avgload",
             :value-json nil,
             :value-type-id 2,
             :value-float 5.64,
             :value-boolean nil,
             :depth 0,
             :value-string nil,
             :value-hash "ee5b587330bf5e2f31eade331c1ec2a1213b7457",
             :value-integer nil}
            {:path "networking#~eth0#~ipaddresses#~0",
             :name "networking",
             :value-json nil,
             :value-type-id 0,
             :value-float nil,
             :value-boolean nil,
             :depth 3,
             :value-string "1.1.1.1",
             :value-hash "fcdd2924e5804c69ee520dcbd31b717b81ed66c5",
             :value-integer nil}]))))

(deftest test-str->num
  (are [n s] (= n (str->num s))

       10 "10"
       123 "123"
       0 "0"
       nil "foo"
       nil "123foo"))

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
