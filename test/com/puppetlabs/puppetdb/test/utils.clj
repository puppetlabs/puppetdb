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
          (is (= "com.puppetlabs.puppetdb.utils" category))
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
