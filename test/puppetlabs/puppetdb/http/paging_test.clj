(ns puppetlabs.puppetdb.http.paging-test
  (:require [puppetlabs.puppetdb.http :as http]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq]]))

(use-fixtures :each with-test-db with-http-app)

(def versions [:v4])

(def types ["events"
            "event-counts"
            "fact-names"
            "facts"
            "nodes"
            "reports"])

(deftestseq paging-options
  [version versions
   type types
   :let [endpoint (str "/" (name version) "/" type)]]

  (testing "'order-by' should properly handle malformed JSON input"
    (let [malformed-JSON  "[{\"field\":\"status\" \"order\":\"DESC\"}]"
          response        (*app* (get-request endpoint
                                              ["these" "are" "unused"]
                                              {:order-by malformed-JSON}))
          body            (get response :body "null")]
      (is (= (:status response) http/status-bad-request))
      (is (re-find #"Illegal value '.*' for :order-by; expected a JSON array of maps" body))))

  (testing "'limit' should only accept positive non-zero integers"
    (doseq [invalid-limit [0
                           -1
                           1.1
                           "\"1\""
                           "\"abc\""]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:limit invalid-limit}))
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find #"Illegal value '.*' for :limit; expected a positive non-zero integer" body)))))

  (testing "'offset' should only accept positive integers"
    (doseq [invalid-offset [-1
                            1.1
                            "\"1\""
                            "\"abc\""]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:offset invalid-offset}))
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find #"Illegal value '.*' for :offset; expected a non-negative integer" body)))))

  (testing "'order-by' :order should only accept nil, 'asc', or 'desc' (case-insensitive)"
    (doseq [invalid-order-by [[{"field" "foo"
                                "order" "foo"}]
                              [{"field" "foo"
                                "order" 1}]]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:order-by (json/generate-string invalid-order-by)}))
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find #"Illegal value '.*' in :order-by; 'order' must be either 'asc' or 'desc'" body))))))
