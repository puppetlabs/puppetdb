(ns com.puppetlabs.puppetdb.test.http.paging
  (:require [com.puppetlabs.http :as pl-http])
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
                    ]]

    (testing (str endpoint " 'order-by' should properly handle malformed JSON input")
      (let [malformed-JSON  "[{\"field\":\"status\" \"order\":\"DESC\"}]"
            response        (*app* (get-request endpoint
                                                ["these" "are" "unused"]
                                                {:order-by malformed-JSON}))
            body            (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find #"Illegal value '.*' for :order-by" body))))

    (testing (str endpoint " 'limit' should only accept non-negative integers")
      (doseq [invalid-limit [-1
                             1.1
                             "\"1\""
                             "\"abc\""
                             ]]
        (let [response  (*app* (get-request endpoint
                                            ["these" "are" "unused"]
                                            {:limit invalid-limit}))
              body      (get response :body "null")]
          (is (= (:status response) pl-http/status-bad-request))
          (is (re-find #"Illegal value '.*' for :limit; expected a non-negative integer" body)))))))
