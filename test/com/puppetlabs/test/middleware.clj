(ns com.puppetlabs.test.middleware
  (:require [com.puppetlabs.utils :as utils]
            [com.puppetlabs.http :as pl-http]
            [fs.core :as fs]
            [ring.util.response :as rr])
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
