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

(deftest jdk-support-status-behavior
  (is (= :no (jdk-support-status "1.5")))
  (is (= :no (jdk-support-status "1.5.0")))
  (is (= :no (jdk-support-status "1.6")))
  (is (= :no (jdk-support-status "1.6.0")))
  (is (= :unknown (jdk-support-status "1.60")))
  (is (= :unknown (jdk-support-status "1.60.1")))
  (is (= :unknown (jdk-support-status "1.7")))
  (is (= :unknown (jdk-support-status "1.7.0")))
  (is (= :unknown (jdk-support-status "1.9")))
  (is (= :unknown (jdk-support-status "1.9.0")))
  (is (= :unknown (jdk-support-status "huh?")))
  (is (= :official (jdk-support-status "1.8")))
  (is (= :official (jdk-support-status "1.8.0")))
  (is (= :tested (jdk-support-status "10")))
  (is (= :tested (jdk-support-status "10.0"))))

(deftest describe-and-return-jdk-status-behavior
  (letfn [(check [version invalid?]
            (let [status (jdk-support-status version)
                  [returned err log]
                  (let [err (java.io.StringWriter.)]
                    (binding [*err* err]
                      (with-log-output log-output
                        (let [s (describe-and-return-jdk-status version)]
                          [s (str err) @log-output]))))]
              (is (= returned status))
              (if-not invalid?
                (do
                  (is (= "" err))
                  (is (= [] log)))
                (do
                  (is (re-matches #"(?s)error: PuppetDB doesn't support.*" err))
                  (is (= 1 (count log)))
                  (let [[[category level _ msg]] log]
                    (is (= "puppetlabs.puppetdb.utils" category))
                    (is (= :error level))
                    (is (re-matches #"PuppetDB doesn't support.*" msg)))))))]
    (check "1.5.0" true)
    (check "1.8.0" false)
    (check "1.10.0" false)
    (check "huh?" false)))

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
