(ns puppetlabs.puppetdb.mq-test
  (:import [org.apache.activemq ScheduledMessage]
           [org.apache.activemq.broker BrokerService])
  (:require [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.mq :refer :all :as mq]
            [puppetlabs.puppetdb.testutils :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [slingshot.test]))

(use-fixtures :each call-with-test-logging-silenced)

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
          (send-message! conn "queue" tracer-msg)
          (is (= [tracer-msg]
                 (map :body (bounded-drain-into-vec! conn "queue" 1)))))))

    (testing "should respect delayed message sending properties"
      (let [tracer-msg "This is a test message"]
        (with-test-broker "test" conn
          (send-message! conn "queue" tracer-msg (delay-property 3 :seconds))
          ;; After 500ms, there should be nothing in the queue
          (is (= [] (timed-drain-into-vec! conn "queue" 500)))
          (Thread/sleep 500)
          ;; Within another 3s, we should see the message, we leave some
          ;; give or take here to componsate for the fact that the MQ
          ;; scheduler resolves delays to a second tick boundary (ignoring
          ;; or diluting milliseconds) so may appear to take almost 4 seconds
          ;; sometimes. This is to avoid potential races in the test.
          (is (= [tracer-msg]
                 (map :body (timed-drain-into-vec! conn "queue" 3000)))))))))

(deftest test-jmx-enabled
  (without-jmx
   (svc-utils/with-puppetdb-instance
     (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"status 404"
                           (svc-utils/current-queue-depth))))))
