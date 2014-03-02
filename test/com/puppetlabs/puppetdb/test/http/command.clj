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
            [clj-time.format :as time])
  (:import [java.io ByteArrayInputStream]))


(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

(defn get-request*
  "Makes a parameter only request"
  [path params]
  (tu/get-request path nil params))

(defn post-request*
  "Marks a post body request"
  [path params payload]
  (let [body (if (nil? payload)
               nil
               (ByteArrayInputStream. (.getBytes payload "UTF-8")))]
    (tu/post-request path nil params {"content-type" "application/json"
                                      "accept" "application/json"} body)))

(def command-app-v3 (command-app :v3))
(def command-app-v4 (command-app :v3))

(deftest command-endpoint
  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload  "This is a test"
            checksum (kitchensink/utf8-string->sha1 payload)
            req (fixt/internal-request {"payload" payload "checksum" checksum})
            api-v3-resp (command-app-v3 req)
            api-v4-resp (command-app-v4 req)
            v2-resp (fixt/*app* (get-request* "/v2/commands" {"payload" payload "checksum" checksum}))
            v3-resp (fixt/*app* (get-request* "/v3/commands" {"payload" payload "checksum" checksum}))
            v4-resp (fixt/*app* (post-request* "/v4/commands" {"checksum" checksum} payload))]
        (tu/assert-success! api-v3-resp)
        (tu/assert-success! api-v4-resp)
        (tu/assert-success! v2-resp)
        (tu/assert-success! v3-resp)
        (tu/assert-success! v4-resp)

        (is (= (tu/content-type api-v3-resp)
               (tu/content-type api-v4-resp)
               (tu/content-type v2-resp)
               (tu/content-type v3-resp)
               (tu/content-type v4-resp)
               pl-http/json-response-content-type))
        (is (tu/uuid-in-response? api-v3-resp))
        (is (tu/uuid-in-response? api-v4-resp))
        (is (tu/uuid-in-response? v2-resp))
        (is (tu/uuid-in-response? v3-resp))
        (is (tu/uuid-in-response? v4-resp))))

    (testing "should return status-bad-request when missing payload"
      (let [api-v3-resp (command-app-v3 (fixt/internal-request {}))
            api-v4-resp (command-app-v4 (fixt/internal-request {}))
            v2-resp (fixt/*app* (tu/get-request "/v2/commands"))
            v3-resp (fixt/*app* (tu/get-request "/v3/commands"))
            v4-resp (fixt/*app* (post-request* "/v4/commands" nil nil))]
        (is (= (:status api-v3-resp)
               (:status api-v4-resp)
               (:status v2-resp)
               (:status v3-resp)
               (:status v4-resp)
               pl-http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [api-v3-resp (command-app-v3 (fixt/internal-request {"payload" "my payload!"}))
            api-v4-resp (command-app-v4 (fixt/internal-request {"payload" "my payload!"}))
            v2-resp (fixt/*app* (get-request* "/v2/commands" {"payload" "my payload!"}))
            v3-resp (fixt/*app* (get-request* "/v3/commands" {"payload" "my payload!"}))
            v4-resp (fixt/*app* (post-request* "/v4/commands" nil "my payload!"))]
        (tu/assert-success! api-v3-resp)
        (tu/assert-success! api-v4-resp)
        (tu/assert-success! v2-resp)
        (tu/assert-success! v3-resp)
        (tu/assert-success! v4-resp)))

    (testing "should return 400 when checksums don't match"
      (let [api-v3-resp (command-app-v3 (fixt/internal-request {"payload" "Testing" "checksum" "something bad"}))
            api-v4-resp (command-app-v4 (fixt/internal-request-post "Testing" {"checksum" "something bad"}))
            v2-resp (fixt/*app* (get-request* "/v2/commands" {"payload" "Testing" "checksum" "something bad"}))
            v3-resp (fixt/*app* (get-request* "/v3/commands" {"payload" "Testing" "checksum" "something bad"}))
            v4-resp (fixt/*app* (post-request* "/v4/commands" {"checksum" "something bad"} "Testing"))]
        (is (= (:status api-v3-resp)
               (:status api-v4-resp)
               (:status v2-resp)
               (:status v3-resp)
               (:status v4-resp)
               pl-http/status-bad-request))))))

(defn round-trip-date-time
  "Parse a DateTime string, then emits the string from that DateTime"
  [date]
  (->> date
       (time/parse (time/formatters :date-time))
       (time/unparse (time/formatters :date-time))))

(deftest receipt-timestamping-v3
  (let [good-payload  (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum (kitchensink/utf8-string->sha1 good-payload)
        bad-payload   "some test message"
        bad-checksum  (kitchensink/utf8-string->sha1 bad-payload)]
    (-> {"payload" good-payload "checksum" good-checksum}
        fixt/internal-request
        command-app-v3)
    (-> {"payload" bad-payload "checksum" bad-checksum}
        fixt/internal-request
        command-app-v3)

    (let [[good-msg bad-msg] (mq/bounded-drain-into-vec! fixt/*conn* "com.puppetlabs.puppetdb.commands" 2)
          good-command       (json/parse-string good-msg true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-command [:annotations :received])]
          (time/parse (time/formatters :date-time) timestamp)))

      (testing "should be left alone when not parseable"
        (is (= bad-msg bad-payload))))))

(deftest receipt-timestamping-v4
  (let [good-payload  (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum (kitchensink/utf8-string->sha1 good-payload)
        bad-payload   "some test message"
        bad-checksum  (kitchensink/utf8-string->sha1 bad-payload)]
    (-> (fixt/internal-request-post good-payload {"checksum" good-checksum})
        command-app-v4)
    (-> (fixt/internal-request-post bad-payload {"checksum" bad-checksum})
        command-app-v4)

    (let [[good-msg bad-msg] (mq/bounded-drain-into-vec! fixt/*conn* "com.puppetlabs.puppetdb.commands" 2)
          good-command (json/parse-string (:body good-msg) true)
          received-time (get-in good-msg [:headers :received])]
      (testing "should be timestamped when parseable"
        (is (= received-time (round-trip-date-time received-time)))
        (is (map? good-command)))

      (testing "should be left alone when not parseable"
        (is (= (:body bad-msg) bad-payload))))))
