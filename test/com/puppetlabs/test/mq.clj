(ns com.puppetlabs.test.mq
  (:use [com.puppetlabs.mq]
        [com.puppetlabs.cmdb.testutils]
        [clojure.test]))

(deftest embedded-broker
  (testing "embedded broker"

    (testing "should give out what it takes in"
      (let [tracer-msg "This is a test message"]
        (with-test-broker "test" conn
          (connect-and-publish! conn "queue" tracer-msg)
          (is (= [tracer-msg] (drain-into-vec! conn "queue" 1000))))))))
