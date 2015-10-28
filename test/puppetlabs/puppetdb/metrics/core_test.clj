(ns puppetlabs.puppetdb.metrics.core-test
  (:import (java.util.concurrent TimeUnit))
  (:require [puppetlabs.puppetdb.http :as http]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.metrics.core :refer :all]
            [puppetlabs.puppetdb.metrics.server :as server]
            [puppetlabs.puppetdb.testutils
             :refer [content-type dotestseq get-request]]))

(def api-versions ["/v1"])

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

(defn accepts-plain-text
  "Changes the request to handle text/plain responses"
  [req]
  (assoc-in req [:headers "accept"] "text/plain"))

(deftest metrics-set-handler
  (dotestseq [version api-versions
              :let [app (server/build-app nil)
                    mbeans-endpoint (str version "/mbeans")]]
    (testing "Remote metrics endpoint"
      (testing "should return a http/status-not-found for an unknown metric"
        (let [response (app (get-request (str mbeans-endpoint "/does_not_exist")))]
          (is (= (:status response)
                 http/status-not-found))))

      (testing "should return a http/status-not-acceptable for unacceptable content type"
        (let [response (app (accepts-plain-text (get-request mbeans-endpoint)))]
          (is (= (:status response)
                 http/status-not-acceptable))))

      (testing "should return a http/status-ok for an existing metric"
        (let [response (app (get-request (str mbeans-endpoint "/java.lang:type=Memory")))]
          (is (= (:status response)
                 http/status-ok))
          (is (= (content-type response)
                 http/json-response-content-type))
          (is (true? (map? (json/parse-string (:body response) true))))))

      (testing "should return a list of all mbeans"
        (let [response (app (get-request mbeans-endpoint))]
          (is (= (:status response)
                 http/status-ok))
          (is (= (content-type response)
                 http/json-response-content-type))

          ;; Retrieving all the resulting mbeans should work
          (let [api-mbeans (json/parse-string (:body response))]

            (is (map? api-mbeans))

            (doseq [[_ uri] (take 100 api-mbeans)
                    :let [response (app
                                    (get-request
                                     (str version uri)))]]
              (is (= (:status response)
                     http/status-ok))
              (is (= (content-type response)
                     http/json-response-content-type)))))))))
