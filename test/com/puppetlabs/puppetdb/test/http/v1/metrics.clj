(ns com.puppetlabs.puppetdb.test.http.v1.metrics
  (:import (java.util.concurrent TimeUnit))
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use com.puppetlabs.puppetdb.http.v1.metrics
        com.puppetlabs.puppetdb.fixtures
        clojure.test
        ring.mock.request
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

(deftest mean-filtering
  (testing "MBean filtering"
    (testing "should pass-through serializable values"
      (is (= (filter-mbean {:key 123})
             {:key 123}))

      (testing "in nested structures"
        (is (= (filter-mbean {:key {:key 123}})
               {:key {:key 123}}))))

    (testing "should stringify unserializable objects"
      (is (= (filter-mbean {:key TimeUnit/SECONDS})
             {:key "SECONDS"}))

      (testing "in nested structures"
        (is (= (filter-mbean {:key {:key TimeUnit/SECONDS}})
               {:key {:key "SECONDS"}}))))))

(def c-t "application/json")

(defn make-request
  "Return a GET request against path, suitable as an argument to the
  metrics app."
  ([path] (make-request path {}))
  ([path {keys [:content-type] :or {:content-type c-t} :as params}]
     (let [request (request :get (format "/v1/metrics/%s" path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "accept" (:content-type params))))))

(deftest metrics-set-handler
  (testing "Remote metrics endpoint"
    (testing "should return a pl-http/status-not-found for an unknown metric"
      (let [request (make-request "mbean/does_not_exist")
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))))

    (testing "should return a pl-http/status-not-acceptable for unacceptable content type"
      (let [request (make-request "mbeans" {:content-type "text/plain"})
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-acceptable))))

    (testing "should return a pl-http/status-ok for an existing metric"
      (let [request (make-request "mbean/java.lang:type=Memory")
            response (*app* request)]
        (is (= (:status response) pl-http/status-ok))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (map? (json/parse-string (:body response) true))
               true))))

    (testing "should return a list of all mbeans"
      (let [request (make-request "mbeans")
            response (*app* request)]
        (is (= (:status response) pl-http/status-ok))
        (is (= (get-in response [:headers "Content-Type"]) c-t))

        ;; Retrieving all the resulting mbeans should work
        (let [mbeans (json/parse-string (:body response))]
          (is (= (map? mbeans) true))
          (doseq [[name uri] mbeans
                  :let [request (make-request uri)]]
            (is (= (:status response pl-http/status-ok)))
            (is (= (get-in response [:headers "Content-Type"]) c-t))))))))
