(ns com.puppetlabs.puppetdb.test.http.paging
  (:require [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        [com.puppetlabs.puppetdb.testutils :only [get-request]]))

(use-fixtures :each with-test-db with-http-app)

(deftest paging-options
  (doseq [endpoint ["/v3/events"
                    "/v3/event-counts"
                    "/v3/fact-names"
                    "/v3/facts"
                    "/v3/nodes"
                    "/v3/reports"
                    "/v3/resources"
                    "/v4/events"
                    "/v4/event-counts"
                    "/v4/fact-names"
                    "/v4/facts"
                    "/v4/nodes"
                    "/v4/reports"
                    "/v4/resources"]]

    (testing (str endpoint " 'order-by' should properly handle malformed JSON input")
      (let [malformed-JSON  "[{\"field\":\"status\" \"order\":\"DESC\"}]"
            response        (*app* (get-request endpoint
                                                ["these" "are" "unused"]
                                                {:order-by malformed-JSON}))
            body            (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find #"Illegal value '.*' for :order-by; expected a JSON array of maps" body))))

    (testing (str endpoint " 'limit' should only accept positive non-zero integers")
      (doseq [invalid-limit [0
                             -1
                             1.1
                             "\"1\""
                             "\"abc\""]]
        (let [response  (*app* (get-request endpoint
                                            ["these" "are" "unused"]
                                            {:limit invalid-limit}))
              body      (get response :body "null")]
          (is (= (:status response) pl-http/status-bad-request))
          (is (re-find #"Illegal value '.*' for :limit; expected a positive non-zero integer" body)))))

    (testing (str endpoint " 'offset' should only accept positive integers")
      (doseq [invalid-offset [-1
                              1.1
                              "\"1\""
                              "\"abc\""]]
        (let [response  (*app* (get-request endpoint
                                           ["these" "are" "unused"]
                                           {:offset invalid-offset}))
              body      (get response :body "null")]
          (is (= (:status response) pl-http/status-bad-request))
          (is (re-find #"Illegal value '.*' for :offset; expected a non-negative integer" body)))))

    (testing (str endpoint " 'order-by' :order should only accept nil, 'asc', or 'desc' (case-insensitive)")
      (doseq [invalid-order-by [[{"field" "foo"
                                 "order" "foo"}]
                                [{"field" "foo"
                                 "order" 1}]]]
        (let [response  (*app* (get-request endpoint
                                 ["these" "are" "unused"]
                                 {:order-by (json/generate-string invalid-order-by)}))
              body      (get response :body "null")]
          (is (= (:status response) pl-http/status-bad-request))
          (is (re-find #"Illegal value '.*' in :order-by; 'order' must be either 'asc' or 'desc'" body)))))))
