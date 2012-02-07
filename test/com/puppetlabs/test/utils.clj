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

(deftest quotient-test
  (testing "quotient"

    (testing "should behave like '/' when divisor is non-zero"
      (is (= 22/7 (quotient 22 7))))

    (testing "should return default when divisor is zero"
      (is (= 0 (quotient 1 0)))
      (is (= 10 (quotient 1 0 10))))))

(deftest conneg
  (testing "should match an exact accept header"
    (is (= "text/html" (acceptable-content-type "text/html" "text/html"))))

  (testing "should match an exact accept header that includes other types"
    (is (= "text/html" (acceptable-content-type "text/html" "text/html, text/plain"))))

  (testing "should match a wildcard accept header"
    (is (= "text/*" (acceptable-content-type "text/html" "text/*"))))

  (testing "should match a wildcard accept header that includes other types"
    (is (= "text/*" (acceptable-content-type "text/html" "text/plain, text/*"))))

  (testing "should return nil if a single header doesn't match"
    (is (= nil (acceptable-content-type "text/html" "application/json"))))

  (testing "should return nil if no headers match"
    (is (= nil (acceptable-content-type "text/html" "text/plain, application/json")))
    (is (= nil (acceptable-content-type "text/html" "text/plain, application/*")))))
