(ns com.puppetlabs.puppetdb.test.http.v3.server-time
  (:require [cheshire.core :as json])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        [clj-time.core :only [ago secs interval in-secs]]
        [clj-time.coerce :only [from-string]]
        [com.puppetlabs.puppetdb.testutils :only (assert-success! get-request)]))

(use-fixtures :each with-http-app)

(defn get-response
  []
  (*app* (get-request "/v3/server-time")))

(deftest server-time-response
  (testing "should return the server time"
    (let [test-time (ago (secs 1))
          response  (get-response)]
      (assert-success! response)
      (let [server-time (-> response
                          :body
                          (json/parse-string true)
                          :server-time
                          from-string)]
        (is (> (in-secs (interval test-time server-time)) 0))
        (is (> 5 (in-secs (interval test-time server-time))))))))

