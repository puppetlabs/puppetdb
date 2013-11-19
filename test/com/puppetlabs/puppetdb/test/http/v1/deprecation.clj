(ns com.puppetlabs.puppetdb.test.http.v1.deprecation
  (:require [ring.mock.request :as mock]
            [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]])
  (:use     [clojure.test]
            [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-http-app)

(def c-t pl-http/json-response-content-type)

(defn get-request
  [path]
  (let [request (mock/request :get path)
        headers (:headers request)]
    (assoc request :headers (assoc headers "Accept" c-t))))

(defn get-response
  ([url] (*app* (get-request url))))

(deftest test-v1-deprecation
  (testing "URLs with no version are deprecated"
    (with-log-output logs
      (let [response (get-response "/version")
            msg      #"Use of unversioned APIs is deprecated"]
        (is (= 1 (count (logs-matching msg @logs))))
        (let [dep-header ((response :headers) "X-Deprecation")]
          (is dep-header)
          (is (re-find msg dep-header))))))
  (testing "URLs with /v1 are deprecated"
    (with-log-output logs
      (let [response  (get-response "/v1/version")
            msg       #"v1 query API is deprecated and will be removed in an upcoming release"]
        (is (= 1 (count (logs-matching msg @logs))))
        (let [dep-header ((response :headers) "X-Deprecation")]
          (is dep-header)
          (is (re-find msg dep-header)))))))
