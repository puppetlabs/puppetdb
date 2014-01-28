(ns com.puppetlabs.puppetdb.test.http.v3.command
  (:require [com.puppetlabs.utils :as pl-utils]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clj-time.format :as time])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]
        [com.puppetlabs.puppetdb.testutils :only [get-request uuid-in-response? assert-success!]]
        [com.puppetlabs.mq]))

(def endpoint "/v3/commands")

(use-fixtures :each with-test-db with-test-mq with-http-app)

(deftest command-endpoint
  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload  "This is a test"
            checksum (pl-utils/utf8-string->sha1 payload)
            req      (get-request endpoint nil {:payload payload :checksum checksum})
            resp     (*app* req)]
        (assert-success! resp)
        (is (= (get-in resp [:headers "Content-Type"]) pl-http/json-response-content-type))
        (is (true? (uuid-in-response? resp)))))

    (testing "should return status-bad-request when missing payload"
      (let [req  (get-request endpoint)
            resp (*app* req)]
        (is (= (:status resp) pl-http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [req (get-request endpoint nil {:payload "my payload!"})
            resp (*app* req)]
        (assert-success! resp)))

    (testing "should return 400 when checksums don't match"
      (let [req  (get-request endpoint nil {:payload "Testing" :checksum "something bad"})
            resp (*app* req)]
        (is (= (:status resp) pl-http/status-bad-request))))))

(deftest receipt-timestamping
  (let [good-payload       (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum      (pl-utils/utf8-string->sha1 good-payload)
        bad-payload        "some test message"
        bad-checksum       (pl-utils/utf8-string->sha1 bad-payload)]
    (->> {:payload good-payload :checksum good-checksum}
      (get-request endpoint nil)
      (*app*))
    (->> {:payload bad-payload :checksum bad-checksum}
      (get-request endpoint nil)
      (*app*))

    (let [[good-msg bad-msg] (bounded-drain-into-vec! *conn* "com.puppetlabs.puppetdb.commands" 2)
          good-command       (json/parse-string good-msg true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-command [:annotations :received])]
          (is (not (nil? (time/parse (time/formatters :date-time) timestamp))))))

      (testing "should be left alone when not parseable"
        (is (= bad-msg bad-payload))))))
