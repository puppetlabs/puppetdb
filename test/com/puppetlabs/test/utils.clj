(ns com.puppetlabs.test.utils
  (:require [fs.core :as fs])
  (:use [com.puppetlabs.utils]
        [com.puppetlabs.puppetdb.testutils]
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

(deftest datetime?-test
  (testing "should return false for non-coercible types"
    (is (not (datetime? 2.0))))
  (testing "should return false for nil"
    (is (not (datetime? nil))))
  (testing "should return true for a valid string"
    (is (datetime? "2011-01-01T12:00:00-03:00")))
  (testing "should return false for an invalid string"
    (is (not (datetime? "foobar"))))
  (testing "should return true for a valid integer"
    (is (datetime? 20)))
  (testing "should return false for an invalid integer")
    (is (not (datetime? -9999999999999999999999999999999))))

(deftest quotient-test
  (testing "quotient"

    (testing "should behave like '/' when divisor is non-zero"
      (is (= 22/7 (quotient 22 7))))

    (testing "should return default when divisor is zero"
      (is (= 0 (quotient 1 0)))
      (is (= 10 (quotient 1 0 10))))))

(deftest mapvals-test
  (testing "should default to applying a function to all of the keys"
    (is (= {:a 2 :b 3} (mapvals inc {:a 1 :b 2}))))
  (testing "should support applying a function to a subset of the keys"
    (is (= {:a 2 :b 2} (mapvals inc [:a] {:a 1 :b 2}))))
  (testing "should support keywords as the function to apply to all of the keys"
    (is (= {:a 1 :b 2} (mapvals :foo {:a {:foo 1} :b {:foo 2}}))))
  (testing "should support keywords as the function to apply to a subset of the keys"
    (is (= {:a 1 :b {:foo 2}} (mapvals :foo [:a] {:a {:foo 1} :b {:foo 2}})))))

(deftest maptrans-test
  (testing "should fail if the keys-fns param isn't valid"
    (is (thrown? AssertionError (maptrans "blah" {:a 1 :b 1}))))
  (testing "should transform a map based on the given functions"
    (is (= {:a 3 :b 3 :c 3 :d 3}
          (maptrans {[:a :b] inc [:d] dec} {:a 2 :b 2 :c 3 :d 4}))))
  (testing "should accept keywords as functions in the keys-fns param"
    (is (= {:a 3 :b 3}
          (maptrans {[:a :b] :foo} {:a {:foo 3} :b {:foo 3}})))))

(deftest dissoc-if-nil-test
  (let [testmap {:a 1 :b nil}]
    (testing "should remove the key if the value is nil"
      (is (= (dissoc testmap :b) (dissoc-if-nil testmap :b))))
    (testing "should not remove the key if the value is not nil"
      (is (= testmap (dissoc-if-nil testmap :a))))))

(defn simple-work-stack
  ;; TODO docs
  [size]
  {:pre  [(pos? size)]}
  (let [original-work   (range size)
        remaining-work  (atom (range size))
        counter         (atom 0)
        iterator-fn     (fn []
      (swap! counter inc)
      (let [old-work  (swap-and-return-old-val! remaining-work next)
            next-item (first old-work)]
        next-item))]
    {:original-work   original-work
     :remaining-work  remaining-work
     :counter         counter
     :iterator-fn     iterator-fn}))

(deftest test-iterator-fn->lazy-seq
  (let [{:keys [counter iterator-fn]} (simple-work-stack 5)
        iterator-seq                  (iterator-fn->lazy-seq iterator-fn)]
    (testing "iterator-fn is not called until seq is accessed"
      (is (= 0 @counter)))
    (testing "iterator-fn is called exactly once if we take one item from the seq"
      (is (= 0 (first iterator-seq)))
      (is (= 1 @counter)))
    (testing "iterator-fn is not called again if we take the same item from the seq again"
      (is (= 0 (first iterator-seq)))
      (is (= 1 @counter)))
    (testing "iterator-fn is called one more time if we take the first two items from the seq"
      (is (= '(0 1) (clojure.core/take 2 iterator-seq)))
      (is (= 2 @counter)))
    (testing "iterator-fn is called once for each item in the seq, plus once for the final nil if we walk the whole seq"
      (doseq [i iterator-seq] i)
      (is (= 6 @counter)))))

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

(deftest cert-utils
  (testing "extracting cn from a dn"
    (is (thrown? AssertionError (cn-for-dn 123))
        "should throw error when arg is a number")
    (is (thrown? AssertionError (cn-for-dn nil))
        "should throw error when arg is nil")

    (is (= (cn-for-dn "") nil)
        "should return nil when passed an empty string")
    (is (= (cn-for-dn "MEH=bar") nil)
        "should return nil when no CN is present")
    (is (= (cn-for-dn "cn=foo.bar.com") nil)
        "should return nil when CN present but lower case")
    (is (= (cn-for-dn "cN=foo.bar.com") nil)
        "should return nil when CN present but with mixed case")

    (is (= (cn-for-dn "CN=foo.bar.com") "foo.bar.com")
        "should work when only CN is present")
    (is (= (cn-for-dn "CN=foo.bar.com,OU=something") "foo.bar.com")
        "should work when more than just the CN is present")
    (is (= (cn-for-dn "CN=foo.bar.com,OU=something") "foo.bar.com")
        "should work when more than just the CN is present")
    (is (= (cn-for-dn "OU=something,CN=foo.bar.com") "foo.bar.com")
        "should work when more than just the CN is present and CN is last")
    (is (= (cn-for-dn "OU=something,CN=foo.bar.com,D=foobar") "foo.bar.com")
        "should work when more than just the CN is present and CN is in the middle")
    (is (= (cn-for-dn "CN=foo.bar.com,CN=goo.bar.com,OU=something") "goo.bar.com")
        "should use the most specific CN if multiple CN's are present")))

(deftest cert-whitelist-auth
  (testing "cert whitelist authorizer"
    (testing "should fail when whitelist is not given"
      (is (thrown? AssertionError (cn-whitelist->authorizer nil))))

    (testing "should fail when whitelist is given, but not readable"
      (is (thrown? java.io.FileNotFoundException
                   (cn-whitelist->authorizer "/this/does/not/exist"))))

    (testing "when whitelist is present"
      (let [whitelist (fs/temp-file)]
        (.deleteOnExit whitelist)
        (spit whitelist "foo\nbar\n")

        (let [authorized? (cn-whitelist->authorizer whitelist)]
          (testing "should allow plain-text, HTTP requests"
            (is (authorized? {:scheme :http :ssl-client-cn "foobar"})))

          (testing "should fail HTTPS requests without a client cert"
            (is (not (authorized? {:scheme :https}))))

          (testing "should reject certs that don't appear in the whitelist"
            (is (not (authorized? {:scheme :https :ssl-client-cn "goo"}))))

          (testing "should accept certs that appear in the whitelist"
            (is (authorized? {:scheme :https :ssl-client-cn "foo"}))))))))

(deftest memoization
  (testing "with an illegal bound"
    (is (thrown? AssertionError (bounded-memoize identity -1)))
    (is (thrown? AssertionError (bounded-memoize identity 0)))
    (is (thrown? AssertionError (bounded-memoize identity 1.5)))
    (is (thrown? AssertionError (bounded-memoize identity "five"))))

  (testing "with a legal bound"
    (let [f (call-counter)
          memoized (bounded-memoize f 2)]
      (testing "should only call the function once per argument"
        (is (= (times-called f) 0))

        (memoized 0)
        (is (= (times-called f) 1))

        (memoized 0)
        (is (= (times-called f) 1))

        (memoized 1)
        (is (= (times-called f) 2)))

      ;; We call it here for a hit, which we expect not to clear the cache,
      ;; then call it again to verify the cache wasn't cleared and therefore f
      ;; wasn't called
      (testing "should not clear the cache at max size on a hit"
        (memoized 1)
        (is (= (times-called f) 2))

        (memoized 1)
        (is (= (times-called f) 2)))

      ;; Now call it with a new argument to clear the cache, then with an old
      ;; one to show f is called
      (testing "should clear the cache at max size on a miss"
        (memoized 2)
        (is (= (times-called f) 3))

        (memoized 3)
        (is (= (times-called f) 4))))))
