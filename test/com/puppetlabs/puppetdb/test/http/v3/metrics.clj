(ns com.puppetlabs.puppetdb.test.http.v3.metrics
  (:import (java.util.concurrent TimeUnit))
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.http.v1.metrics :as metrics])
  (:use com.puppetlabs.puppetdb.fixtures
        clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.testutils :only [get-request]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(def endpoint "/v3/metrics")

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(deftest mean-filtering
  (testing "MBean filtering"
    (testing "should pass-through serializable values"
      (is (= (metrics/filter-mbean {:key 123})
             {:key 123}))

      (testing "in nested structures"
        (is (= (metrics/filter-mbean {:key {:key 123}})
               {:key {:key 123}}))))

    (testing "should stringify unserializable objects"
      (is (= (metrics/filter-mbean {:key TimeUnit/SECONDS})
             {:key "SECONDS"}))

      (testing "in nested structures"
        (is (= (metrics/filter-mbean {:key {:key TimeUnit/SECONDS}})
               {:key {:key "SECONDS"}}))))))

(deftest metrics-set-handler
  (testing "Remote metrics endpoint"
    (testing "should return a pl-http/status-not-found for an unknown metric"
      (let [request (get-request (str endpoint "/mbean/does_not_exist"))
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))))

    (testing "should return a pl-http/status-not-acceptable for unacceptable content type"
      (let [request (get-request (str endpoint "/mbeans") nil {} {"accept" "text/plain"})
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-acceptable))))

    (testing "should return a pl-http/status-ok for an existing metric"
      (let [request (get-request (str endpoint "/mbean/java.lang:type=Memory"))
            response (*app* request)]
        (is (= (:status response) pl-http/status-ok))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (map? (json/parse-string (:body response) true))
               true))))

    (testing "should return a list of all mbeans"
      (let [request (get-request (str endpoint "/mbeans"))
            response (*app* request)]
        (is (= (:status response) pl-http/status-ok))
        (is (= (get-in response [:headers "Content-Type"]) c-t))

        ;; Retrieving all the resulting mbeans should work
        (let [mbeans (json/parse-string (:body response))]
          (is (= (map? mbeans) true))
          (doseq [[name uri] mbeans
                  :let [request (get-request (str endpoint "/" uri))]]
            (is (= (:status response pl-http/status-ok)))
            (is (= (get-in response [:headers "Content-Type"]) c-t))))))))
