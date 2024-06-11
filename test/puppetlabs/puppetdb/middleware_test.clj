(ns puppetlabs.puppetdb.middleware-test
  (:require [puppetlabs.kitchensink.core :as kitchensink :refer [keyset]]
            [puppetlabs.puppetdb.http :as http]
            [ring.util.response :as rr]
            [puppetlabs.puppetdb.middleware
             :refer [build-allowlist-authorizer
                     cause-finder
                     fail-when-payload-too-large
                     merge-param-specs
                     validate-query-params
                     verify-content-encoding
                     verify-content-type
                     wrap-cert-authn
                     wrap-with-certificate-cn
                     wrap-with-metrics] ]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [temp-file]]
            [puppetlabs.ssl-utils.core :refer [get-cn-from-x509-certificate]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-log-output logs-matching]])
  (:import
   (java.net HttpURLConnection)))

(deftest wrapping-metrics
  (testing "Should create per-status metrics"
    (let [storage       (atom {})
          normalize-uri identity]
      (doseq [status (range 200 210)]
        (let [handler (fn [_req] (-> (rr/response nil)
                                     (rr/status status)))
              app (wrap-with-metrics handler storage normalize-uri)]
          (app {:uri "/foo/bar/baz"})))

      ;; Should create both timers and meters
      (is (= #{:timers :meters} (keyset @storage)))

      ;; Should have timers and meters for the given URL
      (is (= #{"/foo/bar/baz"} (keyset (@storage :timers))))
      (is (= #{"/foo/bar/baz"} (keyset (@storage :meters))))

      ;; Should have separate meters for each status code
      (is (= (set (range 200 210)) (keyset (get-in @storage [:meters "/foo/bar/baz"]))))))

  (testing "Should normalize according to supplied func"
    (let [storage       (atom {})
          ;; Normalize urls based on reversing the url
          normalize-uri #(apply str (reverse %))
          handler       (fn [_req] (-> (rr/response nil)
                                       (rr/status HttpURLConnection/HTTP_OK)))
          app           (wrap-with-metrics handler storage normalize-uri)]

      (app {:uri "/foo"})
      (app {:uri "/bar"})
      (app {:uri "/baz"})

      ;; Verify that the metrics are stored using the normalized
      ;; representation
      (is (= #{"oof/" "rab/" "zab/"} (keyset (@storage :timers))))
      (is (= #{"oof/" "rab/" "zab/"} (keyset (@storage :meters)))))))

(deftest find-exception-cause
  (testing "Should find the cause of nested exceptions"
    (let [ex (IllegalArgumentException. (Exception. "The lower exception"))
          ex2 (.initCause (Exception. "First exception") (Exception. "Second exception"))]
      (is (= "The lower exception" (cause-finder ex)))
      (is (= "Second exception" (cause-finder ex2))))))

(defn create-authorizing-request [hostname]
  {:scheme :https
   :ssl-client-cn hostname})

(deftest wrapping-authorization
  (testing "Should only allow authorized requests"
    ;; Setup an app that only lets through odd numbers
    (let [wl (.getAbsolutePath (temp-file "allowlist-log-reject"))
          _ (spit wl "foobar")
          handler     (fn [_req] (-> (rr/response nil)
                                     (rr/status HttpURLConnection/HTTP_OK)))

          message     "The client certificate name"
          app (wrap-cert-authn handler wl)]
      ;; Even numbers should trigger an unauthorized response
      (is (= HttpURLConnection/HTTP_FORBIDDEN
             (:status (app (create-authorizing-request "baz")))))
      ;; The failure reason should be shown to the user
      (is (.contains (:body (app (create-authorizing-request "baz"))) message))
      ;; Odd numbers should get through fine
      (is (= HttpURLConnection/HTTP_OK
             (:status (app (create-authorizing-request "foobar"))))))))

(deftest wrapping-cert-cn-extraction
  (with-redefs [get-cn-from-x509-certificate :cn]
    (let [app (wrap-with-certificate-cn identity)]
      (testing "Should set :ssl-client-cn to extracted cn"
        (let [req {:ssl-client-cert {:cn "foobar"}}]
          (is (= (app req)
                 (assoc req :ssl-client-cn "foobar")))))

      (testing "Should set :ssl-client-cn to extracted cn regardless of URL scheme"
        (let [req {:ssl-client-cert {:cn "foobar"}}]
          (doseq [scheme [:http :https]]
            (is (= (app (assoc req :scheme scheme))
                   (assoc req :scheme scheme :ssl-client-cn "foobar"))))))

      (testing "Should set :ssl-client-cn to nil if no client cert is present"
        (is (= (app {}) {:ssl-client-cn nil})))

      (testing "Should set :ssl-client-cn to nil if cn-for-cert returns nil"
        (let [req {:ssl-client-cert {:meh "meh"}}]
          (is (= (app req)
                 (assoc req :ssl-client-cn nil))))))))

(deftest validating-query-params
  (let [test-string "original test string"
        app-fn      (fn [_req] test-string)
        wrapped-fn  (validate-query-params app-fn
                                           {:required ["foo" "bar"] :optional ["baz" "bam"]})]
    (testing "should do nothing if the params are valid"
      (is (= test-string (wrapped-fn {:params {"foo" 1 "bar" 2 "bam" 3}}))))
    (testing "should return an error response if a required parameter is missing"
      (is (= (wrapped-fn {:params {"foo" 1}})
             {:status HttpURLConnection/HTTP_BAD_REQUEST
              :headers {"Content-Type" http/error-response-content-type}
              :body "Missing required query parameter 'bar'"})))
    (testing "should return an error response if unknown parameters are present"
      (is (= (wrapped-fn {:params {"foo" 1 "bar" 2 "wazzup" 3}})
             {:status HttpURLConnection/HTTP_BAD_REQUEST
              :headers {"Content-Type" http/error-response-content-type}
              :body "Unsupported query parameter 'wazzup'"})))))

(deftest verify-content-type-test
  (testing "with content-type of application/json"
    (let [test-req {:request-method :post
                    :content-type "application/json"
                    :headers {"content-type" "application/json"}}]

      (testing "should succeed with matching content type"
        (let [wrapped-fn   (verify-content-type identity ["application/json"])]
          (is (= (wrapped-fn test-req) test-req))))

      (testing "should fail with no matching content type"
        (let [wrapped-fn   (verify-content-type identity ["application/bson" "application/msgpack"])]
          (is (= (wrapped-fn test-req)
                 {:status 415
                  :headers {"Content-Type" http/error-response-content-type}
                  :body "content type application/json not supported"}))))))

  (testing "with content-type of APPLICATION/JSON"
    (let [test-req {:content-type "APPLICATION/JSON"
                    :headers {"content-type" "APPLICATION/JSON"}}]

      (testing "should succeed with matching content type"
        (let [wrapped-fn   (verify-content-type identity ["application/json"])]
          (is (= (wrapped-fn test-req) test-req))))))

  (testing "with content-type of application/json;parameter=foo"
    (let [test-req {:content-type "application/json;parameter=foo"
                    :headers {"content-type" "application/json;parameter=foo"}}]

      (testing "should succeed with matching content type"
        (let [wrapped-fn   (verify-content-type identity ["application/json"])]
          (is (= (wrapped-fn test-req) test-req)))))))

(deftest verify-content-encoding-test
  (testing "with content-encoding of gzip"
    (let [test-req {:request-method :post
                    :content-type "application/json"
                    :headers {"content-encoding" "gzip"}}]

      (testing "should succeed with matching content encoding"
        (let [wrapped-fn (verify-content-encoding identity ["gzip"])]
          (is (= (wrapped-fn test-req) test-req))))

      (testing "should fail with no matching content encoding"
        (let [wrapped-fn (verify-content-encoding identity ["compress" "deflate"])]
          (is (= (wrapped-fn test-req)
                 {:status 415
                  :headers {"Content-Type" http/error-response-content-type}
                  :body "content encoding gzip not supported"}))))))
  (testing "should succeed with no content-encoding"
    (let [test-req {:request-method :post
                    :content-type "application/json"
                    :headers {}}
          wrapped-fn (verify-content-encoding identity ["whatever"])]
      (is (= (wrapped-fn test-req) test-req)))))

(deftest allowlist-middleware
  (testing "should log on reject"
    (let [wl (temp-file "allowlist-log-reject")]
      (spit wl "foobar")
      (let [authorizer-fn (build-allowlist-authorizer (kitchensink/absolute-path wl))]
        (is (nil? (authorizer-fn {:ssl-client-cn "foobar"})))
        (with-log-output logz
          (is (= 403 (:status (authorizer-fn {:ssl-client-cn "badguy"}))))
          (is (= 1 (count (logs-matching #"^badguy rejected by certificate allowlist " @logz)))))))))

(deftest test-fail-when-payload-too-large
  (testing "max-command-size-fail disabled should allow commands of any size"
    (let [middleware-fn (fail-when-payload-too-large identity false 10)
          post-req {:headers {"content-length" "100000"}
                      :request-method :post
                      :body "foo"}]
      (is (= post-req (middleware-fn post-req)))))

  (testing "reject-large-commands"
    (let [middleware-fn (fail-when-payload-too-large identity true 100)
          test-file (.getAbsolutePath (temp-file "allowlist-log-reject"))]


      (spit test-file "foo")

      (testing "should fail on large request bodies"
        (let [post-req {:headers {"content-length" "1000"}
                        :request-method :post
                        ;; The body of the HTTP request is also an InputStream
                        ;; using a FileInputStream as it's easier to test and
                        ;; ensure that the stream is closed properly
                        :body (java.io.FileInputStream. test-file)}]

          (is (number? (.available (:body post-req))))
          (is (= {:status 413
                  :headers {"Content-Type" http/error-response-content-type}
                  :body "Command rejected due to size exceeding max-command-size"}
                 (middleware-fn post-req)))

          ;; calling .available on a closed FileInputStream with throw
          ;; IOException
          (is (thrown-with-msg? java.io.IOException
                                #"Stream Closed"
                                (.available (:body post-req))))))

      (testing "should fail on large compressed requests when X-Uncompressed-Length is set"
        (let [post-req {:headers {"x-uncompressed-length" "1000"}
                        :request-method :post
                        :body (java.io.FileInputStream. test-file)}]
          (is (= {:status 413
                  :headers {"Content-Type" http/error-response-content-type}
                  :body "Command rejected due to size exceeding max-command-size"}
                 (middleware-fn post-req)))))

      (testing "should log warning when X-Uncompressed-Length header value is invalid"
        (let [post-req {:headers {"x-uncompressed-length" "3.14"}
                        :request-method :post
                        :body (java.io.FileInputStream. test-file)}]
          (with-log-output logz
            ;; if the X-Uncompressed-Length header can't be converted to an integer it is
            ;; ignored and we don't update metrics or use it to reject commands based on size
            (is (= post-req (middleware-fn post-req)))
            (is (= 1 (count (logs-matching #"^The X-Uncompressed-Length value 3.14 cannot be converted to a long" @logz)))))))

      (testing "should log warning when neither Content-Length or X-Uncompressed-Length is set"
        (let [post-req {:headers {}
                        :request-method :post
                        :body (java.io.FileInputStream. test-file)}]
          (with-log-output logz
            (is (= post-req (middleware-fn post-req)))
            (is (= 1 (count (logs-matching #"^Neither Content-Length or X-Uncompressed-Length header is set" @logz)))))))

      (testing "should have no affect on small content"
        (let [post-req {:headers {"content-length" "10"}
                        :request-method :post
                        :body "foo"}
              get-req {:headers {"content-length" "10"}
                       :request-method :get
                       :query-params {"foo" "bar"}}]
          (is (= post-req (middleware-fn post-req)))
          (is (= get-req (middleware-fn get-req))))))))

(defn setify-map-vals [m]
  (into {} (for [[k v] m] [k (set v)])))

(deftest merge-param-specs-behavior
  (is (= nil (merge-param-specs)))
  (is (= {:optional ["x"] :required ["y"]}
         (merge-param-specs {:optional ["x"] :required ["y"]})))
  (is (= {:optional ["x"] :required ["y"] :x :y}
         (merge-param-specs {:optional ["x"] :required ["y"]} {:x :y})))
  (is (= {:optional (set ["x" "z"]) :required (set ["y"])}
         (setify-map-vals
          (merge-param-specs {:optional ["x"] :required ["y"]}
                             {:optional ["z"]}))))
  (is (= {:optional (set ["x" "z"]) :required (set ["v" "w" "y"])}
         (setify-map-vals
          (merge-param-specs {:optional ["x"] :required ["y"]}
                             {:optional ["z"]
                              :required ["v" "w"]})))))
