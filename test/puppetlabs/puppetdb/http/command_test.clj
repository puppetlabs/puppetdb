(ns puppetlabs.puppetdb.http.command-test
  (:require [clojure.core.async :as async]
            [clojure.math.combinatorics :refer [combinations]]
            [clojure.set :as set]
            [clojure.string :as str]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.command :as tgt
             :refer [min-supported-commands valid-commands-str]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils
             :refer [*command-app*
                     *mq*
                     get-request post-request
                     content-type uuid-in-response?
                     assert-success! ]]
            [puppetlabs.puppetdb.testutils.http
             :refer [deftest-command-app internal-request]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.mq :as mq]
            [clj-time.format :as time])
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayInputStream]))

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
    (post-request path params {"content-type" "application/json"
                               "accept" "application/json"} body)))

(defn form-command
  [command version payload]
  (json/generate-string
    {:command command
     :version version
     :payload payload}))

(deftest-command-app command-endpoint
  [[version endpoint] endpoints]

  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload (form-command "replace facts"
                                  (get min-supported-commands "replace facts")
                                  {})
            checksum (kitchensink/utf8-string->sha1 payload)
            req (internal-request {"payload" payload "checksum" checksum})
            response (*command-app* (post-request* endpoint
                                                   {"checksum" checksum}
                                                   payload))]
        (assert-success! response)

        (is (= (content-type response)
               http/json-response-content-type))
        (is (uuid-in-response? response))))

    (testing "should return status-bad-request when missing payload"
      (let [response (*command-app* (post-request* endpoint nil nil))]
        (is (= (:status response)
               http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [payload (form-command "deactivate node"
                                  (get min-supported-commands "deactivate node")
                                  {})
            response (*command-app* (post-request* endpoint nil payload))]
        (assert-success! response)))

    (testing "should 400 when the command is invalid"
      (let [invalid-command (form-command "foo" 100 {})
            invalid-checksum (kitchensink/utf8-string->sha1 invalid-command)
            {:keys [status body]} (*command-app*
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
                                               {})
            misversioned-checksum (kitchensink/utf8-string->sha1 misversioned-command)
            {:keys [status body]} (*command-app*
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

(deftest-command-app receipt-timestamping
  [[version endpoint] endpoints]

  (let [good-payload  (form-command "replace facts"
                                    (get min-supported-commands "replace facts")
                                    {})
        good-checksum (kitchensink/utf8-string->sha1 good-payload)
        request       (fn [payload checksum]
                        (post-request* endpoint {"checksum" checksum} payload))]
    (*command-app* (request good-payload good-checksum))

    (let [[good-msg] (mq/bounded-drain-into-vec!
                       (:connection *mq*)
                       conf/default-mq-endpoint
                       1)
          good-command (json/parse-string (:body good-msg) true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-msg [:headers :received])]
          (time/parse (time/formatters :date-time) timestamp))))))

(deftest wrap-with-request-normalization-all-params
  (let [normalize (#'tgt/wrap-with-request-normalization identity)]
    ;; Make sure that when all three params are present, the body is passed on.
    (let [before-params {"certname" "x" "command" "y_z" "version" "1"
                         "received" (kitchensink/timestamp)}
          before {:params before-params
                  :body (ByteArrayInputStream. (byte-array 0))}
          after (normalize before)
          after-params (:params after)]
      ;; Accomodate the fact that command and version will be transformed.
      (is (identical? (:body before) (:body after)))
      (is (= (dissoc before :params) (dissoc after :params)))
      (is (= (before-params "version") (str (after-params "version"))))
      (is (= (str/replace (before-params "command") "_" " ")
             (after-params "command")))
      (is (= (dissoc before-params "version" "command")
             (dissoc after-params "version" "command"))))))

(deftest wrap-with-request-normalization-no-params
  (let [normalize (#'tgt/wrap-with-request-normalization identity)]
    ;; Check that when there are no parameters, they're extracted from the body
    ;; and returned in :params.
    (let [body-str (json/generate-string
                    {"command" "x y"
                     "version" 1
                     "payload" {"certname" "z"}})
          body-stream  (ByteArrayInputStream. (.getBytes body-str "UTF-8"))
          body-parsed (json/parse-string body-str)]
      (doseq [body [body-str body-stream]]
        (let [before {:params {} :body body}
              after (normalize before)
              after-params (:params after)]
          (is (= (body-parsed "payload")
                 (json/parse-string (:body after))))
          (is (= {"command" "x y" "version" 1 "certname" "z"}
                 (:params after))))))))

(deftest wrap-with-request-normalization-some-params
  (let [normalize (#'tgt/wrap-with-request-normalization identity)]
    ;; Check for an error if some but not all params are present
    (doseq [items (apply concat
                         (map #(combinations ["command" "version" "certname"] %)
                              [1 2]))]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Input to normalize-(old|new)-request does not match schema"
           (normalize {:body ::ignored
                       :params (into {} (for [k items] [k "1"]))}))))))

;; Right now, this is the only unit test that tests the (eventually
;; streaming) command/version/certname params POST.  The acceptance
;; tests test it via the altered terminus.
(deftest-command-app almost-streaming-post
  [[version endpoint] endpoints]
  (let [replace-ver (get min-supported-commands "replace facts")
        payload (form-command "replace facts" replace-ver {})
        checksum (kitchensink/utf8-string->sha1 payload)
        req (internal-request {"payload" payload "checksum" checksum})
        preq (post-request* endpoint
                            {"command" "replace_facts"
                             "certname" "foo"
                             "version" (str replace-ver)
                             "checksum" checksum}
                            payload)
        response (*command-app* preq)]
    (assert-success! response)
    (is (= (content-type response)
           http/json-response-content-type))
    (is (uuid-in-response? response))))

(deftest enqueue-max-command-size
  ;; Does not check blocking-submit-command yet.
  (testing "size limit"
    (tgt/with-chan [chan (async/chan)]
      (let [pub (async/pub chan :id)
            enqueuer (fn [limit]
                       (#'tgt/enqueue-command-handler (fn [& args] true)
                                                      (fn [] pub)
                                                      limit))
            req {:request-method :post :body "more than ten characters"}
            wait-req (assoc req :params {"secondsToWaitForCompletion" "0.001"})]

        ;; These cases differ because we want to skip the processing
        ;; via timeout in the "success" case.
        (testing "when disabled, allows larger size"
          (testing "(without timeout),"
            (is (= http/status-ok
                   (:status ((enqueuer false) req)))))
          (testing "(with timeout),"
            (testing "when disabled, allows larger size"
              (let [response ((enqueuer false) wait-req)]
                (is (= http/status-unavailable (:status response)))
                (is (= true
                       ((json/parse-string (:body response)) "timed_out")))))))

        ;; These cases should behave the same in the with/without cases
        (doseq [[case-name req] [["(without timeout)," req]
                                 ["(with timeout)," wait-req]]]
          (testing case-name
            (testing "when enabled, rejects excessive string requests"
              (is (= http/status-entity-too-large
                     (:status ((enqueuer 10) req)))))
            (testing "when enabled, rejects excessive stream requests"
              (is (= http/status-entity-too-large
                     (:status ((enqueuer 10)
                               (assoc req :body
                                      (ByteArrayInputStream.
                                       (.getBytes (:body req)))))))))))))))
