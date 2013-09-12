(ns com.puppetlabs.puppetdb.test.http.v3.aggregate-event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        [clj-time.core :only [now]]
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        [com.puppetlabs.puppetdb.testutils.event-counts :only [get-response]]
        [com.puppetlabs.puppetdb.testutils.report :only [store-example-report!]]))

(use-fixtures :each with-test-db with-http-app)

(deftest query-aggregate-event-counts
  (store-example-report! (:basic reports) (now))

  (testing "summarize-by rejects unsupported values"
    (let [response  (get-response "/v3/aggregate-event-counts"
                                  ["=" "certname" "foo.local"]
                                  "illegal-summarize-by"
                                  {} true)
          body      (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"Unsupported value for 'summarize-by': 'illegal-summarize-by'" body))))

  (testing "count-by rejects unsupported values"
    (let [response  (get-response "/v3/aggregate-event-counts"
                                  ["=" "certname" "foo.local"]
                                  "node"
                                  {"count-by" "illegal-count-by"} true)
          body      (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"Unsupported value for 'count-by': 'illegal-count-by'" body))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  {:successes 0
                     :failures 0
                     :noops 0
                     :skips 1
                     :total 1}
          response  (get-response "/v3/aggregate-event-counts"
                                  ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                   "containing-class"
                                   {"count-by"      "node"
                                    "counts-filter" ["<" "successes" 1]})
          actual    (json/parse-string (:body response) true)]
      (is (= actual expected)))))
