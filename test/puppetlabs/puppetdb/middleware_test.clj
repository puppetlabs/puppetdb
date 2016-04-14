(ns puppetlabs.puppetdb.middleware-test
  (:import [java.io ByteArrayInputStream])
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [ring.util.response :as rr]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.middleware :refer :all]
            [puppetlabs.kitchensink.core :refer [keyset]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [block-until-results temp-file]]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]))

(deftest wrapping-metrics
  (testing "Should create per-status metrics"
    (let [storage       (atom {})
          normalize-uri identity]
      (doseq [status (range 200 210)]
        (let [handler (fn [req] (-> (rr/response nil)
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
          handler       (fn [req] (-> (rr/response nil)
                                      (rr/status http/status-ok)))
          app           (wrap-with-metrics handler storage normalize-uri)]

      (app {:uri "/foo"})
      (app {:uri "/bar"})
      (app {:uri "/baz"})

      ;; Verify that the metrics are stored using the normalized
      ;; representation
      (is (= #{"oof/" "rab/" "zab/"} (keyset (@storage :timers))))
      (is (= #{"oof/" "rab/" "zab/"} (keyset (@storage :meters)))))))

(defn create-authorizing-request [hostname]
  {:scheme :https
   :ssl-client-cn hostname})

(deftest wrapping-authorization
  (testing "Should only allow authorized requests"
    ;; Setup an app that only lets through odd numbers
    (let [wl (.getAbsolutePath (temp-file "whitelist-log-reject"))
          _ (spit wl "foobar")
          handler     (fn [req] (-> (rr/response nil)
                                    (rr/status http/status-ok)))

          message     "The client certificate name"
          app (wrap-with-authorization handler wl)]
      ;; Even numbers should trigger an unauthorized response
      (is (= http/status-forbidden (:status (app (create-authorizing-request "baz")))))
      ;; The failure reason should be shown to the user
      (is (.contains (:body (app (create-authorizing-request "baz"))) message))
      ;; Odd numbers should get through fine
      (is (= http/status-ok (:status (app (create-authorizing-request "foobar"))))))))

(deftest wrapping-cert-cn-extraction
  (with-redefs [kitchensink/cn-for-cert :cn]
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
        app-fn      (fn [req] test-string)
        wrapped-fn  (validate-query-params app-fn
                                           {:required ["foo" "bar"] :optional ["baz" "bam"]})]
    (testing "should do nothing if the params are valid"
      (is (= test-string (wrapped-fn {:params {"foo" 1 "bar" 2 "bam" 3}}))))
    (testing "should return an error response if a required parameter is missing"
      (let [{:keys [status body]} (wrapped-fn {:params {"foo" 1}})]
        (is (= http/status-bad-request status))
        (is (= "Missing required query parameter 'bar'" body))))
    (testing "should return an error response if unknown parameters are present"
      (let [{:keys [status body]} (wrapped-fn {:params {"foo" 1 "bar" 2 "wazzup" 3}})]
        (is (= http/status-bad-request status))
        (is (= "Unsupported query parameter 'wazzup'" body))))))

(deftest verify-content-type-test
  (testing "with content-type of application/json"
    (let [test-req {:content-type "application/json"
                    :headers {"content-type" "application/json"}}]

      (testing "should succeed with matching content type"
        (let [wrapped-fn   (verify-content-type identity ["application/json"])]
          (is (= (wrapped-fn test-req) test-req))))

      (testing "should fail with no matching content type"
        (let [wrapped-fn   (verify-content-type identity ["application/bson" "application/msgpack"])]
          (is (= (wrapped-fn test-req)
                 {:status 415 :headers {}
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

(deftest whitelist-middleware
  (testing "should log on reject"
    (let [wl (temp-file "whitelist-log-reject")]
      (spit wl "foobar")
      (let [authorizer-fn (build-whitelist-authorizer (kitchensink/absolute-path wl))]
        (is (= :authorized (authorizer-fn {:ssl-client-cn "foobar"})))
        (with-log-output logz
          (is (string? (authorizer-fn {:ssl-client-cn "badguy"})))
          (is (= 1 (count (logs-matching #"^badguy rejected by certificate whitelist " @logz)))))))))

(deftest test-fail-when-payload-too-large
  (testing "max-command-size-fail disabled should allow commands of any size"
    (let [middleware-fn (fail-when-payload-too-large identity false 10)
          post-req {:headers {"content-length" "100000"}
                      :request-method :post
                      :body "foo"}]
      (is (= post-req (middleware-fn post-req)))))

  (testing "reject-large-commands"
    (let [middleware-fn (fail-when-payload-too-large identity true 100)
          test-file (.getAbsolutePath (temp-file "whitelist-log-reject"))]


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
                  :headers {}
                  :body "Command rejected due to size exceeding max-command-size"}
                 (middleware-fn post-req)))

          ;; calling .available on a closed FileInputStream with throw
          ;; IOException
          (is (thrown-with-msg? java.io.IOException
                                #"Stream Closed"
                                (.available (:body post-req))))))
      (testing "should have no affect on small content"
        (let [post-req {:headers {"content-length" "10"}
                        :request-method :post
                        :body "foo"}
              get-req {:headers {"content-length" "10"}
                       :request-method :get
                       :query-params {"foo" "bar"}}]
          (is (= post-req (middleware-fn post-req)))
          (is (= get-req (middleware-fn get-req))))))))
