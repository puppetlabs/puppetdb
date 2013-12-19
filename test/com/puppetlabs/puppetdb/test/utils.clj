(ns com.puppetlabs.puppetdb.test.utils
  (:require [com.puppetlabs.puppetdb.utils :refer :all]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]))

(deftest test-println-err
  (is (= "foo\n"
         (tu/with-err-str (println-err "foo"))))

  (is (= "foo bar\n"
         (tu/with-err-str (println-err "foo" "bar")))))

(def jdk-1-6-version "1.6.0_45")

(def jdk-1-7-version "1.7.0_45")

(def deprecation-regex
  (re-pattern (format "Warning - Support for JDK 1.6 has been deprecated.*%s" jdk-1-6-version)))

(deftest test-jdk6?
  (with-redefs [kitchensink/java-version jdk-1-6-version]
    (is (true? (jdk6?))))

  (with-redefs [kitchensink/java-version jdk-1-7-version]
    (is (false? (jdk6?)))))

(deftest deprecated-jdk-logging
  (testing "1.6 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-6-version]
      (pllog/with-log-output log
        (let [result (tu/with-err-str (log-deprecated-jdk))
              [[category level _ msg]] @log]
          (is (= "com.puppetlabs.puppetdb.utils" category))
          (is (= :error level))
          (is (re-find deprecation-regex msg))
          (is (str/blank? result))))))

  (testing "1.7 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-7-version]
      (pllog/with-log-output log
        (let [result (tu/with-err-str (log-deprecated-jdk))]
          (is (empty? @log))
          (is (str/blank? result)))))))

(deftest deprecated-jdk-alerting
  (testing "1.6 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-6-version]
      (pllog/with-log-output log
        (let [result (tu/with-err-str (alert-deprecated-jdk))
              [[category level _ msg]] @log]
          (is (= "com.puppetlabs.puppetdb.utils" category))
          (is (= :error level))
          (is (re-find deprecation-regex msg))
          (is (re-find deprecation-regex result))))))

  (testing "1.7 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-7-version]
      (pllog/with-log-output log
        (let [result (tu/with-err-str (log-deprecated-jdk))]
          (is (empty? @log))
          (is (str/blank? result)))))))
