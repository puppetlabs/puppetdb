(ns com.puppetlabs.puppetdb.test.http.command
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.http.command :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :refer [get-request post-request
                                                       content-type uuid-in-response?
                                                       assert-success! deftestseq]]
            [com.puppetlabs.puppetdb.version :as version]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.mq :as mq]
            [clj-time.format :as time])
  (:import [java.io ByteArrayInputStream]))

(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

(def endpoints [[:v2 "/v2/commands"]
                [:v3 "/v3/commands"]
                [:v4 "/v4/commands"]])

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
            response (fixt/*app* (case version
                                   (:v2 :v3)
                                   (get-request* endpoint {"payload" payload "checksum" checksum})

                                   (post-request* endpoint {"checksum" checksum} payload)))]
        (assert-success! response)

        (is (= (content-type response)
               pl-http/json-response-content-type))
        (is (uuid-in-response? response))))

    (testing "should return status-bad-request when missing payload"
      (let [response (fixt/*app* (case version
                                   (:v2 :v3)
                                   (get-request endpoint)

                                   (post-request* endpoint nil nil)))]
        (is (= (:status response)
               pl-http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [response (fixt/*app* (case version
                                   (:v2 :v3)
                                   (get-request* endpoint {"payload" "my payload!"})

                                   (post-request* endpoint nil "my payload!")))]
        (assert-success! response)))

    (testing "should return 400 when checksums don't match"
      (let [response (fixt/*app* (case version
                                   (:v2 :v3)
                                   (get-request* endpoint {"payload" "Testing"
                                                           "checksum" "something bad"})

                                   (post-request* endpoint {"checksum" "something bad"} "Testing")))]
        (is (= (:status response)
               pl-http/status-bad-request))))))

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
                        (case version
                          (:v2 :v3)
                          (get-request* endpoint {"payload" payload "checksum" checksum})
                          (post-request* endpoint {"checksum" checksum} payload)))]
    (fixt/*app* (request good-payload good-checksum))
    (fixt/*app* (request bad-payload bad-checksum))

    (let [[good-msg bad-msg] (mq/bounded-drain-into-vec! fixt/*conn* "com.puppetlabs.puppetdb.commands" 2)
          good-command       (json/parse-string (:body good-msg) true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-msg [:headers :received])]
          (time/parse (time/formatters :date-time) timestamp)))

      (testing "should be left alone when not parseable"
        (is (= (:body bad-msg) bad-payload))))))
