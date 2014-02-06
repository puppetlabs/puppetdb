(ns com.puppetlabs.puppetdb.test.http.command
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.http.command :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [com.puppetlabs.puppetdb.version :as version]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.mq :as mq]
            [clj-time.format :as time]))


(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

(defn get-request*
  "Makes a parameter only request"
  [path params]
  (tu/get-request path nil params))

(deftest command-endpoint
  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload  "This is a test"
            checksum (kitchensink/utf8-string->sha1 payload)
            req (fixt/internal-request {"payload" payload "checksum" checksum})
            api-resp     (command req)
            v1-resp (fixt/*app* (get-request* "/v1/commands" {"payload" payload "checksum" checksum}))
            v2-resp (fixt/*app* (get-request* "/v2/commands" {"payload" payload "checksum" checksum}))
            v3-resp (fixt/*app* (get-request* "/v3/commands" {"payload" payload "checksum" checksum}))]
        (tu/assert-success! api-resp)
        (tu/assert-success! v1-resp)
        (tu/assert-success! v2-resp)
        (tu/assert-success! v3-resp)

        (is (= (tu/content-type api-resp)
               (tu/content-type v1-resp)
               (tu/content-type v2-resp)
               (tu/content-type v3-resp)
               pl-http/json-response-content-type))
        (is (tu/uuid-in-response? api-resp))
        (is (tu/uuid-in-response? v1-resp))
        (is (tu/uuid-in-response? v2-resp))
        (is (tu/uuid-in-response? v3-resp))))

    (testing "should return status-bad-request when missing payload"
      (let [api-resp     (command (fixt/internal-request {}))
            v1-resp (fixt/*app* (tu/get-request "/v1/commands"))
            v2-resp (fixt/*app* (tu/get-request "/v2/commands"))
            v3-resp (fixt/*app* (tu/get-request "/v3/commands"))]
        (is (= (:status api-resp)
               (:status v1-resp)
               (:status v2-resp)
               (:status v3-resp)
               pl-http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [api-resp (command (fixt/internal-request {"payload" "my payload!"}))
            v1-resp (fixt/*app* (get-request* "/v1/commands" {"payload" "my payload!"}))
            v2-resp (fixt/*app* (get-request* "/v2/commands" {"payload" "my payload!"}))
            v3-resp (fixt/*app* (get-request* "/v3/commands" {"payload" "my payload!"}))]
        (tu/assert-success! api-resp)
        (tu/assert-success! v1-resp)
        (tu/assert-success! v2-resp)
        (tu/assert-success! v3-resp)))

    (testing "should return 400 when checksums don't match"
      (let [api-resp (command (fixt/internal-request {"payload" "Testing" "checksum" "something bad"}))
            v1-resp (fixt/*app* (get-request* "/v1/commands" {"payload" "Testing" "checksum" "something bad"}))
            v2-resp (fixt/*app* (get-request* "/v2/commands" {"payload" "Testing" "checksum" "something bad"}))
            v3-resp (fixt/*app* (get-request* "/v3/commands" {"payload" "Testing" "checksum" "something bad"}))]
        (is (= (:status api-resp)
               (:status v1-resp)
               (:status v2-resp)
               (:status v3-resp)
               pl-http/status-bad-request))))))

(deftest receipt-timestamping
  (let [good-payload       (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum      (kitchensink/utf8-string->sha1 good-payload)
        bad-payload        "some test message"
        bad-checksum       (kitchensink/utf8-string->sha1 bad-payload)]
    (-> {"payload" good-payload "checksum" good-checksum}
        fixt/internal-request
        command)
    (-> {"payload" bad-payload "checksum" bad-checksum}
        fixt/internal-request
        command)

    (let [[good-msg bad-msg] (mq/bounded-drain-into-vec! fixt/*conn* "com.puppetlabs.puppetdb.commands" 2)
          good-command       (json/parse-string good-msg true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-command [:annotations :received])]
          (time/parse (time/formatters :date-time) timestamp)))

      (testing "should be left alone when not parseable"
        (is (= bad-msg bad-payload))))))
