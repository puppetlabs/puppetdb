(ns com.puppetlabs.puppetdb.test.facts
  (:require [com.puppetlabs.puppetdb.facts :refer :all]
            [clojure.test :refer :all]))

(deftest flatten-fact-value-test
  (testing "check basic types work"
    (is (= (flatten-fact-value "foo") "foo"))
    (is (= (flatten-fact-value 3) "3"))
    (is (= (flatten-fact-value true) "true"))
    (is (= (flatten-fact-value {:a :b}) "{\"a\":\"b\"}"))
    (is (= (flatten-fact-value [:a :b]) "[\"a\",\"b\"]"))))

(deftest flatten-fact-value-map-test
  (testing "ensure we get back a flattened set of values"
    (is (= (flatten-fact-value-map {"networking"
                                    {"eth0"
                                     {"ipaddresses" ["192.168.1.1"]}}})
           {"networking" "{\"eth0\":{\"ipaddresses\":[\"192.168.1.1\"]}}"}))))
