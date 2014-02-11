(ns com.puppetlabs.puppetdb.test.http.metrics
  (:import (java.util.concurrent TimeUnit))
  (:require [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.http.metrics :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu]))

(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

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
  (testing "Remote metrics endpoint"
    (testing "should return a pl-http/status-not-found for an unknown metric"
      (let [request (fixt/internal-request)
            api-response ((mbean ["does_not_exist"]) request)
            v2-response (fixt/*app* (tu/get-request "/v2/metrics/does_not_exist"))
            v3-response (fixt/*app* (tu/get-request "/v3/metrics/does_not_exist"))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-not-found))))

    (testing "should return a pl-http/status-not-acceptable for unacceptable content type"
      (let [request (accepts-plain-text (fixt/internal-request))
            api-response (list-mbeans request)
            v2-response (fixt/*app* (accepts-plain-text (tu/get-request "/v2/metrics/mbeans")))
            v3-response (fixt/*app* (accepts-plain-text (tu/get-request "/v3/metrics/mbeans")))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-not-acceptable))))

    (testing "should return a pl-http/status-ok for an existing metric"
      (let [request (fixt/internal-request)
            api-response ((mbean ["java.lang:type=Memory"]) request)
            v2-response (fixt/*app* (tu/get-request "/v2/metrics/mbean/java.lang:type=Memory"))
            v3-response (fixt/*app* (tu/get-request "/v3/metrics/mbean/java.lang:type=Memory"))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-ok))
        (is (= (tu/content-type api-response)
               (tu/content-type v2-response)
               (tu/content-type v3-response)
               pl-http/json-response-content-type))
        (is (true? (map? (json/parse-string (:body api-response) true))))
        (is (true? (map? (json/parse-string (:body v2-response) true))))
        (is (true? (map? (json/parse-string (:body v3-response) true))))))

    (testing "should return a list of all mbeans"
      (let [api-response (list-mbeans (fixt/internal-request))
            v2-response (fixt/*app* (tu/get-request "/v2/metrics/mbeans"))
            v3-response (fixt/*app* (tu/get-request "/v3/metrics/mbeans"))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-ok))
        (is (= (tu/content-type api-response)
               (tu/content-type v2-response)
               (tu/content-type v3-response)
               pl-http/json-response-content-type))

        ;; Retrieving all the resulting mbeans should work
        (let [api-mbeans (json/parse-string (:body api-response))
              v2-mbeans (json/parse-string (:body v2-response))
              v3-mbeans (json/parse-string (:body v3-response))]

          (is (map? api-mbeans))
          (is (map? v2-mbeans))
          (is (map? v3-mbeans))

          (doseq [[name uri] (take 100 api-mbeans)
                  :let [response ((mbean [name]) (fixt/internal-request))]]

            (is (= (:status response pl-http/status-ok)))
            (is (= (tu/content-type response) pl-http/json-response-content-type)))

          (doseq [[name uri] (take 100 v2-mbeans)
                  :let [response (fixt/*app* (tu/get-request (str "/v2" uri))) ]]
            (is (= (:status response pl-http/status-ok)))
            (is (= (tu/content-type response) pl-http/json-response-content-type)))

          (doseq [[name uri] (take 100 v3-mbeans)
                  :let [response (fixt/*app* (tu/get-request (str "/v3" uri)))]]
            (is (= (:status response pl-http/status-ok)))
            (is (= (tu/content-type response) pl-http/json-response-content-type))))))))
