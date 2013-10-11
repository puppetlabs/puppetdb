(ns com.puppetlabs.test.middleware
  (:require [com.puppetlabs.utils :as utils]
            [com.puppetlabs.http :as pl-http]
            [fs.core :as fs]
            [ring.util.response :as rr]
            [cheshire.core :as json])
  (:use [com.puppetlabs.middleware]
        [com.puppetlabs.utils :only (keyset)]
        [clojure.test]))

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
                                      (rr/status pl-http/status-ok)))
          app           (wrap-with-metrics handler storage normalize-uri)]

      (app {:uri "/foo"})
      (app {:uri "/bar"})
      (app {:uri "/baz"})

      ;; Verify that the metrics are stored using the normalized
      ;; representation
      (is (= #{"oof/" "rab/" "zab/"} (keyset (@storage :timers))))
      (is (= #{"oof/" "rab/" "zab/"} (keyset (@storage :meters)))))))

(deftest wrapping-authorization
  (testing "Should only allow authorized requests"
    ;; Setup an app that only lets through odd numbers
    (let [handler     (fn [req] (-> (rr/response nil)
                                    (rr/status pl-http/status-ok)))
          authorized? odd?
          app         (wrap-with-authorization handler authorized?)]
      ;; Even numbers should trigger an unauthorized response
      (is (= pl-http/status-forbidden (:status (app 0))))
      ;; Odd numbers should get through fine
      (is (= pl-http/status-ok (:status (app 1)))))))

(deftest wrapping-cert-cn-extraction
  (with-redefs [utils/cn-for-cert :cn]
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
        (is (= pl-http/status-bad-request status))
        (is (= "Missing required query parameter 'bar'" body))))
    (testing "should return an error response if unknown parameters are present"
      (let [{:keys [status body]} (wrapped-fn {:params {"foo" 1 "bar" 2 "wazzup" 3}})]
        (is (= pl-http/status-bad-request status))
        (is (= "Unsupported query parameter 'wazzup'" body))))))

(deftest wrapping-paging-options
  (let [app-fn      (fn [req] (req :paging-options))
        wrapped-fn  (wrap-with-paging-options app-fn)]
    (testing "should return an error if order-by is not a valid JSON string"
      (let [{:keys [status body]}
              (wrapped-fn {:params {"order-by" "["}})]
        (is (= pl-http/status-bad-request status))
        (is (= "Illegal value '[' for :order-by; expected an array of maps."
              body))))

    (testing "should return an error if order-by is not an array of maps"
      (let [{:keys [status body]}
              (wrapped-fn {:params
                           {"order-by"
                            (json/generate-string {"field" "foo"})}})]
        (is (= pl-http/status-bad-request status))
        (is (= (str "Illegal value '{\"field\":\"foo\"}' for :order-by; "
                 "expected an array of maps.")))))

    (testing "should return an error if an order-by map is missing 'field'"
      (let [{:keys [status body]}
            (wrapped-fn {:params
                         {"order-by"
                          (json/generate-string [{"foo" "bar"}])}})]
        (is (= pl-http/status-bad-request status))
        (is (= (str "Illegal value '{\"foo\":\"bar\"}' in :order-by; "
                 "missing required key 'field'.")))))

    (testing "should return an error if an order-by map has unknown keys"
      (let [{:keys [status body]}
            (wrapped-fn {:params
                         {"order-by"
                          (json/generate-string [{"field" "foo"
                                                  "bar" "baz"}])}})]
        (is (= pl-http/status-bad-request status))
        (is (= (str "Illegal value '{\"field\": \"foo\", \"bar\": \"baz\"}' "
                 "in :order-by; unknown key 'bar'.")))))

    (testing "`count?` should default to `false`"
      (is (= false (:count? (wrapped-fn {:params {}})))))

    (testing "should make paging options available on the request"
      (is (= (wrapped-fn
               {:params
                  {"limit"    "10"
                   "offset"   "10"
                   "order-by" (json/generate-string [{"field" "foo"
                                                      "order" "desc"}])
                   "include-total"   "true"
                   "foo"      "bar"}})
            {:limit     10
             :offset    10
             :order-by  [{:field :foo :order "desc"}]
             :count?    true })))))
