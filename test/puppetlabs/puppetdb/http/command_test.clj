(ns puppetlabs.puppetdb.http.command-test
  (:require [clojure.core.async :as async]
            [clojure.math.combinatorics :refer [combinations]]
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
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.stockpile.queue :as stock]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [puppetlabs.puppetdb.time :as time])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.net HttpURLConnection)
   (java.util.concurrent Semaphore)
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
  [_command _version payload]
  (json/generate-string payload))

(defn create-handler [q]
  (let [command-chan (async/chan 1)
        app (test-command-app q command-chan)]
    [command-chan app]))

(deftest command-endpoint
  (dotestseq
   [[_version endpoint] endpoints]

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

     (testing "should not do checksum verification if no checksum is provided"
       (tqueue/with-stockpile q
         (let [[_ app] (create-handler q)
               payload (form-command "deactivate node"
                                     (get min-supported-commands "deactivate node")
                                     {})
               response (app (post-request* endpoint
                                            {"version" (str (get min-supported-commands "replace facts"))
                                             "certname" "foo.com"
                                             "command" "deactivate node"}
                                            payload))]
           (assert-success! response)))))))

(def endpoint-error-specs
  [{:title "should 400 when missing payload"
    :params {}
    :body {}
    :error-regex #"The request body must be a JSON map with required keys: command, version, payload"}

   {:title "should 400 when the command is invalid"
    :params {"version" "1"
             "certname" "foo.com"
             "command" "print nodes"}
    :body {}
    :error-regex (re-pattern (str "Command must be one of: " valid-commands-str "."))}

   {:title "should 400 when version is retired"
    :params {"certname" "foo.com"
             "command" "replace facts"
             "version" (-> "replace facts" min-supported-commands dec str)}
    :body {}
    :error-regex (re-pattern (str "The minimum supported version is "
                                  (min-supported-commands "replace facts")))}

   {:title "should 400 with non-integer version (new format)"
    :params {"version" "three"
             "command" "deactivate node"
             "certname" "foo.com"}
    :body {"certname" "foo.com"}
    :error-regex #"Version must be a valid integer"}

   {:title "should 400 with non-integer version (old format)"
    :params {}
    :body {"version" "three",
           "command" "deactivate node",
           "payload" {"certname" "foo.com"}}
    :error-regex #"Version must be a valid integer"}

   {:title "should 400 with missing certname (new format)"
    :params {"version" "3"
             "command" "deactivate node"}
    :body {"certname" "foo.com"}
    :error-regex #"Command is missing required parameters: certname"}

   {:title "should 400 with missing certname (old format)"
    :params {}
    :body {"version" 3,
           "command" "deactivate node",
           "payload" {}}
    :error-regex #"Command is missing required parameters: certname"}

   {:title "should 400 with empty certname (new format)"
    :params {"version" "3"
             "certname" ""
             "command" "deactivate node"}
    :body {"certname" "foo.com"}
    :error-regex #"Certname must be a non-empty string"}

   {:title "should 400 with empty certname (old format)"
    :params {}
    :body {"version" 2,
           "command" "deactivate node",
           "payload" {"certname" ""}}
    :error-regex #"Certname must be a non-empty string"}

   {:title "should 400 with unrecognized params (new format)"
    :params {"version" "3"
             "certname" "foo.com"
             "command" "deactivate node"
             "breakfast" "pancakes"}
    :body {"certname" "foo.com"}
    :error-regex #"Command has invalid parameters: breakfast"}

   {:title "should 400 with unrecognized params (old format)"
    :params {}
    :body {"version" 2,
           "command" "deactivate node",
           "breakfast" "pancakes"
           "payload" {"certname" "foo.com"}}
    :error-regex #"Command has invalid parameters: breakfast"}

   {:title "should 400 with invalid JSON body (old format)"
    :params {}
    :body "sfdf[fds[dsfsd{{{{sdf"
    :error-regex #"The request body must be a JSON map with required keys: command, version, payload"}

   {:title "should 400 with empty JSON body (old format)"
    :params {}
    :body {}
    :error-regex #"The request body must be a JSON map with required keys: command, version, payload"}

   {:title "should 400 with nil JSON body (old format)"
    :params {}
    :body nil
    :error-regex #"The request body must be a JSON map with required keys: command, version, payload"}

   {:title "should 400 with invalid payload (old format)"
    :params {}
    :body {"command" "deactivate node"
           "version" 3
           "payload" "bad payload"}
    :error-regex #"The payload value must be a JSON map"}])

(deftest command-endpoint-errors
  (tqueue/with-stockpile q
    (let [[_ app] (create-handler q)]
      (doseq [{:keys [title params body error-regex]} endpoint-error-specs]
        (testing title
          (let [request-body (if (map? body) (json/generate-string body) body)
                {:keys [status body headers]}
                (app (post-request* "/v1" params request-body))
                {:keys [error]} (json/parse-string body true)]
            (is (= status HttpURLConnection/HTTP_BAD_REQUEST))
            (is (= ["Content-Type"] (keys headers)))
            (is (http/json-utf8-ctype? (headers "Content-Type")))
            (is (re-find error-regex error))))))))

(deftest receipt-timestamping
  (dotestseq
   [[_version endpoint] endpoints]

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
  (let [normalize (#'tgt/wrap-with-request-normalization identity)
        before-params {"certname" "x" "command" "y_z" "version" "1"
                       "received" (kitchensink/timestamp)}
        before {:params before-params
                :body (ByteArrayInputStream. (byte-array 0))}
        after (normalize before)
        after-params (:params after)]
    ;; Make sure that when all three params are present, the body is passed on.
    ;; Accomodate the fact that command and version will be transformed.
    (is (identical? (:body before) (:body after)))
    (is (= (dissoc before :params) (dissoc after :params)))
    (is (= (before-params "version") (str (after-params "version"))))
    (is (= (str/replace (before-params "command") "_" " ")
           (after-params "command")))
    (is (= (dissoc before-params "version" "command")
           (dissoc after-params "version" "command")))))

(deftest wrap-with-request-normalization-no-params
  (let [normalize (#'tgt/wrap-with-request-normalization identity)
        body-str (json/generate-string
                   {"command" "x y"
                    "version" 1
                    "payload" {"certname" "z"}})
        body-stream (ByteArrayInputStream. (.getBytes body-str "UTF-8"))
        body-parsed (json/parse-string body-str)]
    ;; Check that when there are no parameters, they're extracted from the body
    ;; and returned in :params.
    (doseq [body [body-str body-stream]]
      (let [before {:params {} :body body}
            after (normalize before)]
        (is (= (body-parsed "payload")
               (json/parse-string (:body after))))
        (is (= {"command" "x y" "version" 1 "certname" "z"}
               (:params after)))))))

(deftest wrap-with-request-params-validation-missing-params
  (let [validate (#'tgt/wrap-with-request-params-validation identity)]
    ;; Check for an error if some but not all params are present
    (doseq [bad-params (apply concat
                         (map #(combinations [["command" "store report"]
                                              ["version" 5]
                                              ["certname" "foo.com"]]
                                             %)
                              [1 2]))
            :let [bad-request {:body ::ignored
                               :params (into {} bad-params)}
                  {:keys [status body]} (validate bad-request)
                  {:strs [error]} (json/parse-string body)]]
      (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
      (is (re-find #"missing required parameters" error)))))

;; Right now, this is the only unit test that tests the (eventually
;; streaming) command/version/certname params POST.  The acceptance
;; tests test it via the altered terminus.
(deftest almost-streaming-post
  (dotestseq
    [[_version endpoint] endpoints]
    (tqueue/with-stockpile q
      (let [replace-ver (get min-supported-commands "replace facts")
            payload (form-command "replace facts" replace-ver {})
            checksum (kitchensink/utf8-string->sha1 payload)
            [_ app] (create-handler q)
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
            (is (= HttpURLConnection/HTTP_OK
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
                (is (= HttpURLConnection/HTTP_UNAVAILABLE (:status response)))
                (is (= true
                       (get (json/parse-string (:body response)) "timed_out")))))))

        ;; These cases should behave the same in the with/without cases
        (doseq [[case-name req] [["(without timeout)," req]
                                 ["(with timeout)," wait-req]]]
          (testing case-name
            (testing "when enabled, rejects excessive string requests"
              (is (= HttpURLConnection/HTTP_ENTITY_TOO_LARGE
                     (:status (max-10-app req)))))
            (testing "when enabled, rejects excessive stream requests"
              (is (= HttpURLConnection/HTTP_ENTITY_TOO_LARGE
                     (:status (max-10-app
                               (assoc req :body
                                      (ByteArrayInputStream.
                                       (.getBytes (:body req)))))))))))))))
