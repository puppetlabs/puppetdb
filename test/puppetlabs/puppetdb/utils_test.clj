(ns puppetlabs.puppetdb.utils-test
  (:require [clojure.math.combinatorics :refer [selections]]
            [puppetlabs.puppetdb.utils :as tgt :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog
             :refer [with-log-output]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as cct]))

(deftest re-quote-behavior
  (doseq [x (map #(apply str %) (selections ["x" "\\Q" "\\E"] 6))]
    (is (= x (re-matches (re-pattern (str (tgt/re-quote x) "$")) x)))))

(deftest test-println-err
  (is (= "foo\n"
         (tu/with-err-str (println-err "foo"))))

  (is (= "foo bar\n"
         (tu/with-err-str (println-err "foo" "bar")))))

(deftest test-assoc-when
  (is (= {:a 1 :b 2}
         (assoc-when {:a 1 :b 2} :b 100)))
  (is (= {:a 1 :b 100}
         (assoc-when {:a 1} :b 100)))
  (is (= {:b 100}
         (assoc-when nil :b 100)))
  (is (= {:b 100}
         (assoc-when {} :b 100)))
  (is (= {:a 1 :b 2 :c  3}
         (assoc-when {:a 1} :b 2 :c 3))))

(deftest stringify-keys-test
  (let [sample-data1 {"foo/bar" "data" "key with space" {"child/foo" "baz"}}
        sample-data2 {:foo/bar "data" :fuz/bash "data2"}
        keys         (walk/keywordize-keys sample-data1)]
    (is (= sample-data1 (stringify-keys keys)))
    (is (= {"foo/bar" "data" "fuz/bash" "data2"} (stringify-keys sample-data2)))))

(deftest describe-bad-base-url-behavior
  (is (not (describe-bad-base-url {:protocol "http" :host "xy" :port 0})))
  (is (string? (describe-bad-base-url {:protocol "http" :host "x:y" :port 0}))))

(deftest test-regex-quote
  (is (thrown? IllegalArgumentException (regex-quote "Rob's \\Ecommand")))
  (let [special-chars "$.^[)"]
    (is (= special-chars (-> (regex-quote special-chars)
                             re-pattern
                             (re-find (format "fo*%s?!" special-chars)))))))

(deftest test-match-any-of
  (let [special-chars [\$ "." \] "()"]
        match-special (re-pattern (match-any-of special-chars))]
    (doseq [special-char special-chars]
      (is (= (str special-char) (-> (re-find match-special (format "con%stext" special-char))
                                    first))))))

(def dash-keyword-generator
  (gen/fmap (comp keyword #(str/join "-" %))
            (gen/not-empty (gen/vector gen/string-alpha-numeric))))

(def underscore-keyword-generator
  (gen/fmap (comp keyword #(str/join "_" %))
            (gen/not-empty (gen/vector gen/string-alpha-numeric))))

(cct/defspec test-dash-conversions
  50
  (prop/for-all [w (gen/map dash-keyword-generator gen/any)]
                (= w
                   (underscore->dash-keys (dash->underscore-keys w)))))

(cct/defspec test-underscore-conversions
  50
  (prop/for-all [w (gen/map underscore-keyword-generator gen/any)]
                (= w
                   (dash->underscore-keys (underscore->dash-keys w)))))

(deftest test-utf8-truncate
  (is (= "ಠ" (utf8-truncate "ಠ_ಠ" 3)))
  (is (= "ಠ_" (utf8-truncate "ಠ_ಠ" 4)))
  (is (= "ಠ_" (utf8-truncate "ಠ_ಠ" 5)))
  (is (= "ಠ_" (utf8-truncate "ಠ_ಠ" 6)))
  (is (= "ಠ_ಠ" (utf8-truncate "ಠ_ಠ" 7)))
  (is (= "ಠ_ಠ" (utf8-truncate "ಠ_ಠಠ" 8)))

  (testing "when string starts with multi-byte character and is truncated to fewer bytes, yields empty string"
    (is (= "" (utf8-truncate "ಠ_ಠ" 1)))
    (is (= "" (utf8-truncate "ಠ_ಠ" 2))))

  (testing "truncation doesn't add extra bytes"
    (is (= "foo" (utf8-truncate "foo" 256)))))
