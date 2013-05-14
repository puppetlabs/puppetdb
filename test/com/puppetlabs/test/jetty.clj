(ns com.puppetlabs.test.jetty
  (:use [com.puppetlabs.jetty]
        [com.puppetlabs.puppetdb.testutils]
        [clojure.test]))

(deftest ciphers
  (testing "buggy JVMs should return a specific set of ciphers to use"
    (is (seq (acceptable-ciphers "1.7.0_20"))))

  (testing "last-known-good JVM version should return a nil set of ciphers"
    (is (nil? (acceptable-ciphers "1.7.0_05"))))

  (testing "unaffected JVM version should return a nil set of ciphers"
    (is (nil? (acceptable-ciphers "1.6.0_05")))))
