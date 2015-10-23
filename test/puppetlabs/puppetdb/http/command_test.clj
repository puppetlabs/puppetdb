(ns puppetlabs.puppetdb.http.command-test
  (:import [java.io ByteArrayInputStream])
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.command :refer [min-supported-commands
                                                      valid-commands-str]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.testutils :refer [get-request post-request
                                                   content-type uuid-in-response?
                                                   assert-success! deftestseq]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.mq :as mq]
            [clj-time.format :as time]))

(use-fixtures :each fixt/call-with-test-db fixt/with-test-mq fixt/with-command-app)

(def endpoints [[:v1 "/v1"]])

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

(defn form-command
  [command version payload]
  (json/generate-string
    {:command command
     :version version
     :payload payload}))

(deftestseq command-endpoint
  [[version endpoint] endpoints]

  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload (form-command "replace facts"
                                  (get min-supported-commands "replace facts")
                                  "{}")
            checksum (kitchensink/utf8-string->sha1 payload)
            req (fixt/internal-request {"payload" payload "checksum" checksum})
            response (fixt/*command-app* (post-request* endpoint
                                                        {"checksum" checksum}
                                                        payload))]
        (assert-success! response)

        (is (= (content-type response)
               http/json-response-content-type))
        (is (uuid-in-response? response))))

    (testing "should return status-bad-request when missing payload"
      (let [response (fixt/*command-app* (post-request* endpoint nil nil))]
        (is (= (:status response)
               http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [payload (form-command "deactivate node"
                                  (get min-supported-commands "deactivate node")
                                  "{}")
            response (fixt/*command-app* (post-request* endpoint nil payload))]
        (assert-success! response)))

    (testing "should return 400 when checksums don't match"
      (let [response (fixt/*command-app* (post-request* endpoint
                                                {"checksum" "something bad"}
                                                "Testing"))]
        (is (= (:status response)
               http/status-bad-request))))

    (testing "should 400 when the command is invalid"
      (let [invalid-command (form-command "foo" 100 "{}")
            invalid-checksum (kitchensink/utf8-string->sha1 invalid-command)
            {:keys [status body]} (fixt/*command-app*
                                    (post-request* endpoint
                                                   {"checksum" invalid-checksum}
                                                   invalid-command))]
        (is (= status
               http/status-bad-request))

        (is (= (:error (json/parse-string body true))
               (format "Supported commands are %s. Received 'foo'."
                       valid-commands-str)))))

    (testing "should 400 when version is retired"
      (let [min-supported-version (get min-supported-commands "replace facts")
            misversioned-command (form-command "replace facts"
                                               (dec min-supported-version)
                                               "{}")
            misversioned-checksum (kitchensink/utf8-string->sha1 misversioned-command)
            {:keys [status body]} (fixt/*command-app*
                                    (post-request* endpoint
                                                   {"checksum" misversioned-checksum}
                                                   misversioned-command))]

        (is (= status
               http/status-bad-request))
        (is (= (:error (json/parse-string body true))
               (format (str "replace facts version %s is retired. "
                            "The minimum supported version is %s.")
                       (dec min-supported-version)
                       min-supported-version)))))))

(defn round-trip-date-time
  "Parse a DateTime string, then emits the string from that DateTime"
  [date]
  (->> date
       (time/parse (time/formatters :date-time))
       (time/unparse (time/formatters :date-time))))

(deftestseq receipt-timestamping
  [[version endpoint] endpoints]

  (let [good-payload  (form-command "replace facts"
                                    (get min-supported-commands "replace facts")
                                    "{}")
        good-checksum (kitchensink/utf8-string->sha1 good-payload)
        request       (fn [payload checksum]
                        (post-request* endpoint {"checksum" checksum} payload))]
    (fixt/*command-app* (request good-payload good-checksum))

    (let [[good-msg] (mq/bounded-drain-into-vec!
                       (:connection fixt/*mq*)
                       conf/default-mq-endpoint
                       1)
          good-command (json/parse-string (:body good-msg) true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-msg [:headers :received])]
          (time/parse (time/formatters :date-time) timestamp))))))

