(ns com.puppetlabs.test.mq
  (:import [org.apache.activemq ScheduledMessage])
  (:require [clamq.jms :as jms])
  (:use [com.puppetlabs.mq]
        [com.puppetlabs.puppetdb.testutils]
        [clojure.test]))

(deftest delay-calc
  (testing "calculation of message delays"
    (testing "should handle unit conversion"
      (is (= {ScheduledMessage/AMQ_SCHEDULED_DELAY "7200000"} (delay-property 7200000)))
      (is (= {ScheduledMessage/AMQ_SCHEDULED_DELAY "7200000"} (delay-property 7200 :seconds)))
      (is (= {ScheduledMessage/AMQ_SCHEDULED_DELAY "7200000"} (delay-property 120 :minutes)))
      (is (= {ScheduledMessage/AMQ_SCHEDULED_DELAY "7200000"} (delay-property 2 :hours)))
      (is (thrown? IllegalArgumentException (delay-property 123 :foobar))))))

(deftest embedded-broker
  (testing "embedded broker"

    (testing "should give out what it takes in"
      (let [tracer-msg "This is a test message"]
        (with-test-broker "test" conn
          (connect-and-publish! conn "queue" tracer-msg)
          (is (= [tracer-msg] (bounded-drain-into-vec! conn "queue" 1))))))

    (testing "should respect delayed message sending properties"
      (let [tracer-msg "This is a test message"]
        (with-test-broker "test" conn
          (connect-and-publish! conn "queue" tracer-msg (delay-property 2 :seconds))
          ;; After 1s, there should be nothing in the queue
          (is (= [] (timed-drain-into-vec! conn "queue" 1000)))
          (Thread/sleep 1000)
          ;; After another 1s, we should see the message
          (is (= [tracer-msg] (bounded-drain-into-vec! conn "queue" 1))))))))

