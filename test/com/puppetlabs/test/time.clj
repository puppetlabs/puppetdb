(ns com.puppetlabs.test.time
  (:use clojure.test
        [clj-time.core :only [days hours secs minutes]]
        com.puppetlabs.time))

(deftest test-periods-equal?
  (testing "should return true for a single period"
    (is (periods-equal? (secs 10))))
  (testing "should return true for periods that are equal"
    (is (periods-equal? (days 2) (hours 48)))
    (is (periods-equal? (hours 3) (minutes 180)))
    (is (periods-equal? (days 1) (hours 24) (minutes (* 60 24)) (secs (* 60 60 24)))))
  (testing "should return false for periods that are not equal"
    (is (not (periods-equal? (days 2) (hours 2))))
    (is (not (periods-equal? (hours 1) (minutes 59))))
    (is (not (periods-equal? (hours 1) (minutes 60) (secs 10))))
    (is (not (periods-equal? (hours 1) (days 1) (minutes 60) (secs (* 60 60 24)))))))

(deftest test-parse-period
  (testing "should successfully parse a value in days"
    (is (periods-equal? (parse-period "2d") (days 2))))
  (testing "should successfully parse a value in hours"
    (is (periods-equal? (parse-period "10h") (hours 10))))
  (testing "should successfully parse a value in minutes"
    (is (periods-equal? (parse-period "120m") (hours 2))))
  (testing "should successfully parse a value in seconds"
    (is (periods-equal? (parse-period "14s") (secs 14))))
  (testing "should successfully parse a value in milliseconds"
    (is (periods-equal? (parse-period "4000ms") (secs 4))))
  (testing "should throw an exception if the string is not valid"
    (is (thrown-with-msg? IllegalArgumentException #"Invalid format: \"foo\""
          (parse-period "foo")))
    (is (thrown-with-msg? IllegalArgumentException #"Invalid format: \"2d 1s\""
          (parse-period "2d 1s")))
    (is (thrown-with-msg? IllegalArgumentException #"Invalid format: \"2 s\""
          (parse-period "2 s")))))

(deftest test-format-period
  (testing "should return a human-readable string for a period"
    (is (= "2 days" (format-period (days 2)))))
  (testing "should normalize when possible, and return a human-readable string"
    (is (= "2 hours" (format-period (minutes 120)))))
  (testing "should use singular versions of time units when appropriate"
    (is (= "1 minute" (format-period (secs 60)))))
  (testing "should not use weeks when normalizing"
    (is (= "30 days" (format-period (days 30)))))
  (testing "should only normalize to the largest whole unit"
    (is (= "121 seconds" (format-period (secs 121))))
    (is (= "2 minutes" (format-period (secs 120))))
    (is (= "26 hours" (format-period (hours 26))))
    (is (= "1 day" (format-period (hours 24))))))

(deftest test-to-days
  (testing "should convert periods in various units to days"
    (is (= 1 (to-days (hours 24))))
    (is (= 2 (to-days (minutes (* 60 24 2)))))))

(deftest test-to-hours
  (testing "should convert periods in various units to hours"
    (is (= 1 (to-hours (minutes 60))))
    (is (= 2 (to-hours (secs (* 60 60 2)))))))

(deftest test-to-minutes
  (testing "should convert periods in various units to minutes"
    (is (= 60 (to-minutes (hours 1))))
    (is (= (* 60 24) (to-minutes (days 1))))))

(deftest test-to-secs
  (testing "should convert periods in various units to seconds"
    (is (= 60 (to-secs (minutes 1))))
    (is (= (* 60 60) (to-secs (hours 1))))))

(deftest test-to-millis
  (testing "should convert periods in various units to millis"
    (is (= 1000 (to-millis (secs 1))))
    (is (= (* 1000 60) (to-millis (minutes 1))))))
