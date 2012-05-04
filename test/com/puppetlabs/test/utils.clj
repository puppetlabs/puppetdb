(ns com.puppetlabs.test.utils
  (:require [fs.core :as fs])
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
  (testing "content negotiation"
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
      (is (= nil (acceptable-content-type "text/html" "text/plain, application/*"))))))

(deftest uri-to-segments
  (testing "splitting a url into segments"
    (testing "should work for partial urls"
      (is (= ["foo" "bar"] (uri-segments "foo/bar"))))

    (testing "should work for empty urls"
      (is (= [] (uri-segments ""))))

    (testing "should work for common urls"
      (is (= ["foo" "bar" "baz"] (uri-segments "/foo/bar/baz"))))

    (testing "should remove empty segments"
      (is (= ["foo" "bar"] (uri-segments "/foo//bar"))))))

(deftest string-hashing
  (testing "Computing a SHA-1 for a UTF-8 string"
    (testing "should fail if not passed a string"
      (is (thrown? AssertionError (utf8-string->sha1 1234))))

    (testing "should produce a stable hash"
      (is (= (utf8-string->sha1 "foobar")
             (utf8-string->sha1 "foobar"))))

    (testing "should produce the correct hash"
      (is (= "8843d7f92416211de9ebb963ff4ce28125932878"
             (utf8-string->sha1 "foobar"))))))

(deftest ini-parsing
  (testing "Parsing ini files"
    (testing "should work for a single file"
      (let [tf (fs/temp-file)]
        (spit tf "[foo]\nbar=baz")

        (testing "when specified as a file object"
          (is (= (inis-to-map tf)
                 {:foo {:bar "baz"}})))

        (testing "when specified as a string"
          (is (= (inis-to-map (fs/absolute-path tf))
                 {:foo {:bar "baz"}})))))

    (testing "should work for a directory"
      (let [td (fs/temp-dir)]
        (testing "when no matching files exist"
          (is (= (inis-to-map td) {})))

        (spit (fs/file td "a.ini") "[foo]\nbar=baz")

        (testing "when only a single matching file exists"
          (is (= (inis-to-map td)
                 {:foo {:bar "baz"}})))

        ;; Now add a second file
        (spit (fs/file td "b.ini") "[bar]\nbar=baz")

        (testing "when multiple matching files exist"
          (is (= (inis-to-map td)
                 {:foo {:bar "baz"}
                  :bar {:bar "baz"}})))

        ;; Now add a file that clobbers data from another
        (spit (fs/file td "c.ini") "[bar]\nbar=goo")

        (testing "when multiple matching files exist"
          (is (= (inis-to-map td)
                 {:foo {:bar "baz"}
                  :bar {:bar "goo"}})))))))
