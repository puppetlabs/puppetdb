(ns puppetlabs.puppetdb.utils-test
  (:require [puppetlabs.puppetdb.utils :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(deftest test-println-err
  (is (= "foo\n"
         (tu/with-err-str (println-err "foo"))))

  (is (= "foo bar\n"
         (tu/with-err-str (println-err "foo" "bar")))))

(def jdk-1-6-version "1.6.0_45")

(def jdk-1-7-version "1.7.0_45")

(def unsupported-regex
  (re-pattern (format ".*JDK 1.6 is no longer supported. PuppetDB requires JDK 1.7\\+, currently running.*%s" jdk-1-6-version)))

(deftest test-jdk6?
  (with-redefs [kitchensink/java-version jdk-1-6-version]
    (is (true? (jdk6?))))

  (with-redefs [kitchensink/java-version jdk-1-7-version]
    (is (false? (jdk6?)))))

(deftest unsupported-jdk-failing
  (testing "1.6 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-6-version]
      (pllog/with-log-output log
        (let [fail? (atom false)
              result (tu/with-err-str (fail-unsupported-jdk #(reset! fail? true)))
              [[category level _ msg]] @log]
          (is (= "puppetlabs.puppetdb.utils" category))
          (is (= :error level))
          (is (re-find unsupported-regex msg))
          (is (re-find unsupported-regex result))
          (is (true? @fail?))))))

  (testing "1.7 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-7-version]
      (pllog/with-log-output log
        (let [fail? (atom false)
              result (tu/with-err-str (fail-unsupported-jdk #(reset! fail? true)))]
          (is (empty? @log))
          (is (str/blank? result))
          (is (false? @fail?)))))))

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
