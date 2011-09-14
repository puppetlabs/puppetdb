(ns com.puppetlabs.test.utils
  (:use [com.puppetlabs.utils]
        [clojure.test]))

(deftest array?-test
  (testing "array?"

    (testing "should work for nil input"
      (is (nil? (array? nil))))

    (testing "should detect primitive arrays"
      (doseq [f #{object-array boolean-array byte-array short-array char-array int-array long-array float-array double-array}]
        (is (true? (array? (f 1))))))

    (testing "should return nil for non-array objects"
      (doseq [x ['() [] {} "foo" 123 456.789 1/3]]
        (is (false? (array? x)))))))
