(ns puppetlabs.puppetdb.cli.util-test
  (:require
   [clojure.test :refer [deftest is]]
   [puppetlabs.puppetdb.cli.util :refer [jdk-support-status]]))

(deftest jdk-support-status-behavior
  (is (= :no (jdk-support-status "1.5")))
  (is (= :no (jdk-support-status "1.5.0")))
  (is (= :no (jdk-support-status "1.6")))
  (is (= :no (jdk-support-status "1.6.0")))
  (is (= :unknown (jdk-support-status "1.60")))
  (is (= :unknown (jdk-support-status "1.60.1")))
  (is (= :unknown (jdk-support-status "huh?")))
  (is (= :no (jdk-support-status "1.7")))
  (is (= :no (jdk-support-status "1.7.0")))
  (is (= :deprecated (jdk-support-status "1.8")))
  (is (= :deprecated (jdk-support-status "1.8.0")))
  (is (= :deprecated (jdk-support-status "1.9")))
  (is (= :deprecated (jdk-support-status "1.9.0")))
  (is (= :deprecated (jdk-support-status "10")))
  (is (= :deprecated (jdk-support-status "10.0")))
  (is (= :tested (jdk-support-status "11.0")))
  (is (= :tested (jdk-support-status "11.0.7")))
  (is (= :official (jdk-support-status "17.0.4"))))
