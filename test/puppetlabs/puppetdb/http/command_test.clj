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
             :refer [get-request post-request
                     content-type uuid-in-response?
                     assert-success!
                     test-command-app
                     dotestseq]]
            [puppetlabs.puppetdb.testutils.http :refer [internal-request]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.stockpile.queue :as stock]
            [puppetlabs.puppetdb.testutils.nio :as nio]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.middleware
             :refer [wrap-with-puppetdb-middleware]]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [puppetlabs.puppetdb.time :as time])
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent Semaphore]
           (java.util.zip GZIPOutputStream)))

(def endpoints [[:v1 "/v1"]])

(defn get-request*
  "Makes a parameter only request"
  [path params]
  (get-request path nil params))

(defn post-request*
  "Makes a post body request"
  [path params payload]
  (let [body (when-not (nil? payload)
               (ByteArrayInputStream. (if (string? payload)
                                        (.getBytes payload "UTF-8")
                                        payload)))]
    (post-request path params {"content-type" "application/json"
                               "accept" "application/json"} body)))

(defn form-command
  [command version payload]
  (json/generate-string payload))

(defn create-handler [q]
  (let [command-chan (async/chan 1)
        app (test-command-app q command-chan)]
    [command-chan app]))

(deftest command-endpoint
  (dotestseq
   [[version endpoint] endpoints]

   (testing "Commands submitted via REST"
     (testing "should work when well-formed"
       (tqueue/with-stockpile q
         (let [payload (form-command "replace facts"
                                     (get min-supported-commands "replace facts")
                                     {:foo 1
                                      :bar 2})
               checksum (kitchensink/utf8-string->sha1 payload)
               [command-chan app] (create-handler q)
               version (str (get min-supported-commands "replace facts"))
               request-params {"checksum" checksum
                               "version" version
                               "certname" "foo.com"
                               "command" "replace facts"}]

           (testing "for raw json requests"
             (let [response (app (post-request* endpoint
                                                request-params
                                                payload))]
               (assert-success! response)

               (let [cmdref (async/<!! command-chan)]
                 (is (= {:foo 1
                         :bar 2}
                        (:payload (queue/cmdref->cmd q cmdref)))))

               (is (http/json-utf8-ctype? (content-type response)))
               (is (uuid-in-response? response))))

           (testing "for gzipped json requests"
             (let [gzipped-payload-stream (ByteArrayOutputStream.)
                   _ (with-open [gzip-output-stream (GZIPOutputStream.
                                                     gzipped-payload-stream)]
                       (->> payload
                            (.getBytes)
                            (.write gzip-output-stream)))
                   gzipped-payload (.toByteArray gzipped-payload-stream)
                   request (post-request* endpoint
                                          request-params
                                          gzipped-payload)
                   request-with-content-encoding (assoc-in request
                                                           [:headers
                                                            "content-encoding"]
                                                           "gzip")
                   response (app request-with-content-encoding)]
               (assert-success! response)

               (let [cmdref (async/<!! command-chan)]
                 (is (= {:foo 1
                         :bar 2}
                        (:payload (queue/cmdref->cmd q cmdref)))))

               (is (http/json-utf8-ctype? (content-type response)))
               (is (uuid-in-response? response)))))))

     (testing "should return status-bad-request when missing payload"
       (tqueue/with-stockpile q
         (let [[command-chan app] (create-handler q)
               {:keys [status body headers]} (app (post-request* endpoint nil nil))]
           (is (= status http/status-bad-request))
           (is (= ["Content-Type"] (keys headers)))
           (is (http/json-utf8-ctype? (headers "Content-Type")))
           (is (= (:error (json/parse-string body true))
                  "Supported commands are configure expiration, deactivate node, replace catalog, replace catalog inputs, replace facts, store report. Received 'null'.")))))

     (testing "should not do checksum verification if no checksum is provided"
       (tqueue/with-stockpile q
         (let [[command-chan app] (create-handler q)
               payload (form-command "deactivate node"
                                     (get min-supported-commands "deactivate node")
                                     {})
               response (app (post-request* endpoint
                                            {"version" (str (get min-supported-commands "replace facts"))
                                             "certname" "foo.com"
                                             "command" "deactivate node"}
                                            payload))]
           (assert-success! response))))

     (testing "should 400 when the command is invalid"
       (tqueue/with-stockpile q
         (let [[command-chan app] (create-handler q)
               invalid-command (form-command "foo" 100 {})
               invalid-checksum (kitchensink/utf8-string->sha1 invalid-command)
               {:keys [status body headers]}
               (app (post-request* endpoint
                                   {"version" "1"
                                    "certname" "foo.com"
                                    "command" "foo"
                                    "checksum" invalid-checksum}
                                   invalid-command))]
           (is (= status http/status-bad-request))
           (is (= ["Content-Type"] (keys headers)))
           (is (http/json-utf8-ctype? (headers "Content-Type")))
           (is (= (:error (json/parse-string body true))
                  (format "Supported commands are %s. Received 'foo'."
                          valid-commands-str))))))

     (testing "should 400 when version is retired"
       (tqueue/with-stockpile q
         (let [[command-chan app] (create-handler q)
               min-supported-version (get min-supported-commands "replace facts")
               misversioned-command (form-command "replace facts"
                                                  (dec min-supported-version)
                                                  {})
               misversioned-checksum (kitchensink/utf8-string->sha1 misversioned-command)
               {:keys [status body headers]}
               (app (post-request* endpoint
                                   {"checksum" misversioned-checksum
                                    "version" (str (dec min-supported-version))
                                    "certname" "foo.com"
                                    "command" "replace facts"}
                                   misversioned-command))]

           (is (= status http/status-bad-request))
           (is (= ["Content-Type"] (keys headers)))
           (is (http/json-utf8-ctype? (headers "Content-Type")))
           (is (= (:error (json/parse-string body true))
                  (format (str "replace facts version %s is retired. "
                               "The minimum supported version is %s.")
                          (dec min-supported-version)
                          min-supported-version)))))))))

(deftest receipt-timestamping
  (dotestseq
   [[version endpoint] endpoints]

   (tqueue/with-stockpile q
     (let [ms-before-test (System/currentTimeMillis)
           [command-chan app] (create-handler q)
           good-payload  (form-command "replace facts"
                                       (get min-supported-commands "replace facts")
                                       {})
           good-checksum (kitchensink/utf8-string->sha1 good-payload)]

       ;; Sleeping to get ensure a time difference
       (Thread/sleep 1)
       (app (post-request* endpoint {"checksum" good-checksum
                                     "certname" "foo.com"
                                     "version" "4"
                                     "command" "replace facts"} good-payload))

       (let [cmdref (async/<!! command-chan)]

         (testing "should be timestamped when parseable"
           (is (< ms-before-test (time/to-long (:received cmdref))))))))))

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
(deftest almost-streaming-post
  (dotestseq
    [[version endpoint] endpoints]
    (tqueue/with-stockpile q
      (let [replace-ver (get min-supported-commands "replace facts")
            payload (form-command "replace facts" replace-ver {})
            checksum (kitchensink/utf8-string->sha1 payload)
            req (internal-request {"payload" payload "checksum" checksum})
            [command-chan app] (create-handler q)
            response (app
                      (post-request* endpoint
                                     {"command" "replace_facts"
                                      "certname" "foo"
                                      "version" (str replace-ver)
                                      "checksum" checksum}
                                     payload))]
        (assert-success! response)
        (is (http/json-utf8-ctype? (content-type response)))
        (is (uuid-in-response? response))))))

(defn handler-with-max [q command-chan max-command-size]
  (#'tgt/enqueue-command-handler
   (fn [command version certname producer-ts stream compression callback]
     (let [maybe-send-cmd-event! (constantly true)]
       (cmd/do-enqueue-command
        q
        command-chan
        (Semaphore. 100)
        (queue/create-command-req command version certname producer-ts compression callback stream)
        maybe-send-cmd-event!)))
   max-command-size))

(deftest enqueue-max-command-size
  ;; Does not check blocking-submit-command yet.
  (tqueue/with-stockpile q
    (testing "size limit"
      (let [command-chan (async/chan 1)
            no-max-app (handler-with-max q command-chan false)
            max-10-app (handler-with-max q command-chan 10)
            req {:request-method :post
                 :body "more than ten characters"
                 :params {"command" "replace catalog"
                          "version" 4
                          "certname" "foo.com"
                          "producer-timestamp" "2018-11-01T00:00:00.000Z"}}
            wait-req (assoc-in req [:params "secondsToWaitForCompletion"] "0.001")]
        ;; These cases differ because we want to skip the processing
        ;; via timeout in the "success" case.
        (testing "when disabled, allows larger size"
          (testing "(without timeout),"
            (is (= http/status-ok
                   (:status (no-max-app req)))))

          (let [test-cmdref (async/<!! command-chan)]
            (is (= "more than ten characters"
                   (->> test-cmdref
                        queue/cmdref->entry
                        (stock/stream q)
                        slurp)))

            ;; test producer-timestamp is included in cmdref
            (is (= "2018-11-01T00:00:00.000Z"
                   (str (:producer-ts test-cmdref)))))

          (testing "(with timeout),"
            (testing "when disabled, allows larger size"
              (let [response (no-max-app wait-req)]
                (is (= http/status-unavailable (:status response)))
                (is (= true
                       (get (json/parse-string (:body response)) "timed_out")))))))

        ;; These cases should behave the same in the with/without cases
        (doseq [[case-name req] [["(without timeout)," req]
                                 ["(with timeout)," wait-req]]]
          (testing case-name
            (testing "when enabled, rejects excessive string requests"
              (is (= http/status-entity-too-large
                     (:status (max-10-app req)))))
            (testing "when enabled, rejects excessive stream requests"
              (is (= http/status-entity-too-large
                     (:status (max-10-app
                               (assoc req :body
                                      (ByteArrayInputStream.
                                       (.getBytes (:body req)))))))))))))))
