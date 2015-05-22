(ns puppetlabs.puppetdb.command-test
  (:import [java.io ByteArrayInputStream])
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.testutils :refer [get-request post-request
                                                   content-type uuid-in-response?
                                                   assert-success! deftestseq]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.mq :as mq]
            [clj-time.format :as time]))

(use-fixtures :each fixt/with-test-db fixt/with-test-mq)

(def endpoints [[:v1 "/v1"]])

(defn with-command-app
  "A fixture to build an HTTP app and make it available as `*app*` within
  tests. This will provide the `*db*` and `*mq*` to the app as globals if they
  are available. Note this means this fixture should be nested _within_
  `with-test-db` or `with-test-mq`."
  [request]
  (let [app (command/build-app
             {:scf-read-db          fixt/*db*
              :scf-write-db         fixt/*db*
              :command-mq           fixt/*mq*})]
    (app request)))

(defn get-request*
  "Makes a parameter only request"
  [path params]
  (get-request path nil params))

(defn post-request*
  "Makes a post body request"
  [path params payload]
  (let [body (when-not (nil? payload)
               (ByteArrayInputStream. (.getBytes payload "UTF-8")))]
    (post-request path nil params {"content-type" "application/json"
                                   "accept" "application/json"} body)))

(deftestseq command-endpoint
  [[version endpoint] endpoints]

  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload  "This is a test"
            checksum (kitchensink/utf8-string->sha1 payload)
            req (fixt/internal-request {"payload" payload "checksum" checksum})
            response (with-command-app
                       (post-request* endpoint {"checksum" checksum} payload))]
        (assert-success! response)

        (is (= (content-type response)
               http/json-response-content-type))
        (is (uuid-in-response? response))))

    (testing "should return status-bad-request when missing payload"
      (let [response (with-command-app (post-request* endpoint nil nil))]
        (is (= (:status response)
               http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [response (with-command-app (post-request* endpoint nil "my payload!"))]
        (assert-success! response)))

    (testing "should return 400 when checksums don't match"
      (let [response (with-command-app (post-request* endpoint {"checksum" "something bad"} "Testing"))]
        (is (= (:status response)
               http/status-bad-request))))))

(defn round-trip-date-time
  "Parse a DateTime string, then emits the string from that DateTime"
  [date]
  (->> date
       (time/parse (time/formatters :date-time))
       (time/unparse (time/formatters :date-time))))

(deftestseq receipt-timestamping
  [[version endpoint] endpoints]

  (let [good-payload  (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum (kitchensink/utf8-string->sha1 good-payload)
        bad-payload   "some test message"
        bad-checksum  (kitchensink/utf8-string->sha1 bad-payload)
        request       (fn [payload checksum]
                        (post-request* endpoint {"checksum" checksum} payload))]
    (with-command-app (request good-payload good-checksum))
    (with-command-app (request bad-payload bad-checksum))

    (let [[good-msg bad-msg] (mq/bounded-drain-into-vec!
                              (:connection fixt/*mq*)
                              "puppetlabs.puppetdb.commands"
                              2)
          good-command (json/parse-string (:body good-msg) true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-msg [:headers :received])]
          (time/parse (time/formatters :date-time) timestamp)))

      (testing "should be left alone when not parseable"
        (is (= (:body bad-msg) bad-payload))))))
