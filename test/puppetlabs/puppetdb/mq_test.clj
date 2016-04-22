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

(deftest corrupt-kahadb-journal
  (testing "corrupt kahadb journal handling"
    (testing "corruption should return exception"
      ;; We are capturing the previous known failure here, just in case in the
      ;; future ActiveMQ changes behaviour (hopefully fixing this problem) so
      ;; we can make a decision about weither capturing EOFException and
      ;; restarting the broker ourselves is still needed.
      ;;
      ;; Upstream bug is: https://issues.apache.org/jira/browse/AMQ-4339
      (let [dir (kitchensink/absolute-path (fs/temp-dir "corrupt-kahadb-handling"))
            broker-name  "test"]
        (try
          ;; Start and stop a broker, then corrupt the journal
          (let [broker (build-embedded-broker broker-name dir)]
            (start-broker! broker)
            (stop-broker! broker)
            (spit (fs/file dir "test" "KahaDB" "db-1.log") "asdf"))
          ;; Upon next open, we should get an EOFException
          (let [broker (build-embedded-broker broker-name dir)]
            (is (thrown? java.io.EOFException (start-broker! broker))))
          ;; Now lets clean up
          (finally
            (fs/delete-dir dir)))))
    (testing "build-and-start-broker! should ignore the corruption"
      ;; Current work-around is to restart the broker upon this kind of
      ;; corruption. This test makes sure this continues to work for the
      ;; lifetime of this code.
      (let [dir (kitchensink/absolute-path (fs/temp-dir "ignore-kahadb-corruption"))
            broker-name "test"]
        (try
          ;; Start and stop a broker, then corrupt the journal
          (let [broker (build-embedded-broker broker-name dir)]
            (start-broker! broker)
            (stop-broker! broker)
            (spit (fs/file dir "test" "KahaDB" "db-1.log") "asdf"))
          ;; Now lets use the more resilient build-and-start-broker!
          (let [broker (build-and-start-broker! broker-name dir {})]
            (stop-broker! broker))
          ;; Now lets clean up
          (finally
            (fs/delete-dir dir)))))))

(deftest test-build-broker
  (testing "build-embedded-broker"
    (try
      (let [broker (build-embedded-broker "somedir")]
        (is (instance? BrokerService broker)))
      (let [broker (build-embedded-broker "localhost" "somedir")]
        (is (instance? BrokerService broker)))
      (let [broker (build-embedded-broker "localhost" "somedir" {})]
        (is (instance? BrokerService broker)))
      (let [size-megs 50
            size-bytes (* size-megs 1024 1024)

            broker (build-embedded-broker "localhost" "somedir"
                                          {:store-usage size-megs
                                           :temp-usage  size-megs})]
        (is (instance? BrokerService broker))
        (is (.. broker (getPersistenceAdapter) (isIgnoreMissingJournalfiles)))
        (is (.. broker (getPersistenceAdapter) (isArchiveCorruptedIndex)))
        (is (.. broker (getPersistenceAdapter) (isCheckForCorruptJournalFiles)))
        (is (.. broker (getPersistenceAdapter) (isChecksumJournalFiles)))
        (is (= size-bytes (.. broker (getSystemUsage) (getStoreUsage) (getLimit))))
        (is (= size-bytes (.. broker (getSystemUsage) (getTempUsage) (getLimit)))))
      (finally
        (fs/delete-dir "somedir")))))

(deftest test-jmx-enabled
  (without-jmx
   (svc-utils/with-puppetdb-instance
     (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"status 404"
                           (svc-utils/current-queue-depth))))))

(deftest test-queue-size
  (with-test-broker "test" conn
    (is (thrown+? [:type ::mq/queue-not-found] (queue-size "test" "foo")))
    (send-message! conn "foo" "1")
    (send-message! conn "bar" "1")
    (send-message! conn "bar" "2")
    (while (and (not= 1 (queue-size "test" "foo"))
                (not= 2 (queue-size "test" "bar")))
      (Thread/sleep 100))
    (bounded-drain-into-vec! conn "foo" 1)
    (bounded-drain-into-vec! conn "bar" 1)
    (is (zero? (queue-size "test" "foo")))
    (is (= 1 (queue-size "test" "bar")))
    (bounded-drain-into-vec! conn "bar" 1)
    (is (zero? (queue-size "test" "bar")))
    (remove-queue! "test" "bar")
    (is (thrown+? [:type ::mq/queue-not-found] (queue-size "test" "bar")))
    (is (zero? (queue-size "test" "foo")))
    (remove-queue! "test" "foo")
    (is (thrown+? [:type ::mq/queue-not-found] (queue-size "test" "foo")))))

(deftest test-remove-queue!
  (with-test-broker "test" conn
    (is (thrown+? [:type ::mq/queue-not-found] (queue-size "test" "foo")))
    (remove-queue! "test" "foo")
    (send-message! conn "foo" "1")
    (bounded-drain-into-vec! conn "foo" 1)
    (is (zero? (queue-size "test" "foo")))
    (remove-queue! "test" "foo")
    (is (thrown+? [:type ::mq/queue-not-found] (queue-size "test" "foo")))))

(deftest test-transfer-messages!
  (with-test-broker "test" conn
    ;; Once when dest doesn't already exist, and once when it does.
    (dotimes [_ 2]
      (send-message! conn "foo" "1")
      (send-message! conn "foo" "2")
      (send-message! conn "foo" "3")
      (while (not= 3 (queue-size "test" "foo"))
        (Thread/sleep 100))
      (transfer-messages! "test" "foo" "bar")
      (is (zero? (queue-size "test" "foo")))
      (is (= 3 (queue-size "test" "bar")))
      (is (= [{:headers {} :body "1"}
              {:headers {} :body "2"}
              {:headers {} :body "3"}]
             (bounded-drain-into-vec! conn "bar" 3))))))
