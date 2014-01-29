(ns com.puppetlabs.puppetdb.test.http.v2.fact-names
  (:require [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request]]))

(def endpoint "/v2/fact-names")

(use-fixtures :each with-test-db with-http-app)

(deftest fact-names-queries
  (testing "should not support paging-related query parameters"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [request (get-request endpoint nil {k v})
            {:keys [status body]} (*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k))))))))
