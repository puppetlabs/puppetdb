(ns com.puppetlabs.puppetdb.test.http.experimental.eventcounts
  (:require [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        [com.puppetlabs.puppetdb.testutils :only (response-equal?)]
        [com.puppetlabs.puppetdb.testutils.report :only [store-example-report!]]
        [clj-time.core :only [now]]))

(use-fixtures :each with-test-db with-http-app)

(def content-type-json pl-http/json-response-content-type)

(defn- json-encode-counts-filter
  [params]
  (if-let [counts-filter (params "counts-filter")]
    (assoc params "counts-filter" (json/generate-string counts-filter))
    params))

(defn- get-request
  [path query summarize-by extra-query-params]
  (let [params  (-> extra-query-params
                    (assoc "query" (json/generate-string query))
                    (assoc "summarize-by" summarize-by)
                    (json-encode-counts-filter))
        request (request :get path params)
        headers (:headers request)]
    (assoc request :headers (assoc headers "Accept" content-type-json))))

(defn- get-response
  ([query summarize-by]
    (get-response query summarize-by {}))
  ([query summarize-by extra-query-params]
    (*app* (get-request "/experimental/event-counts" query summarize-by extra-query-params))))

(deftest query-event-counts
  (let [_ (store-example-report! (:basic reports) (now))]

    (testing "summarize-by rejects unsupported values"
      (let [response  (get-response ["=" "certname" "foo.local"] "illegal-summarize-by")
            body      (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find #"Unsupported value for 'summarize-by': 'illegal-summarize-by'" body))))

    (testing "count-by rejects unsupported values"
      (let [response  (get-response ["=" "certname" "foo.local"] "certname" {"count-by" "illegal-count-by"})
            body      (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find #"Unsupported value for 'count-by': 'illegal-count-by'" body))))

    (testing "nontrivial query using all the optional parameters"
      (let [expected  #{{:containing_class "Foo"
                         :failures 0
                         :successes 0
                         :noops 0
                         :skips 1}}
            response  (get-response ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                     "containing-class"
                                     {"count-by"      "node"
                                      "counts-filter" ["<" "successes" 1]})]
        (response-equal? response expected)))))
