(ns com.puppetlabs.puppetdb.test.http.v2.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request]]
        [com.puppetlabs.utils :only [mapvals]]
        [com.puppetlabs.puppetdb.testutils.resources :only [store-example-resources]]))

(def endpoint "/v2/resources")

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(defn get-response
  ([]      (get-response nil))
  ([query] (get-response query nil))
  ([query params] (*app* (get-request endpoint query params))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok   (:status response)))
  (is (= c-t (get-in response [:headers "Content-Type"])))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil))))

(defn expected-results
  "Munge example resource output from latest API format to v2 format"
  [example-resources]
  (mapvals
    #(clojure.set/rename-keys % {:file :sourcefile :line :sourceline})
    example-resources))

(deftest test-resource-queries
  (let [{:keys [foo1 foo2 bar1 bar2]} (expected-results (store-example-resources))]
    (testing "query by source file / line"
      (let [query ["=" "sourcefile" "/foo/bar"]
            result #{bar2}]
        (is-response-equal (get-response query) result))
      (let [query ["~" "sourcefile" "foo"]
            result #{bar2}]
        (is-response-equal (get-response query) result))
      (let [query ["=" "sourceline" 22]
            result #{bar2}]
        (is-response-equal (get-response query) result)))

    (testing "query by new field names file/line"
      (let [query ["=" "line" 22]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "line is not a queryable object for resources" (:body response))))
      (let [query ["~" "file" "foo"]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "file cannot be the target of a regexp match" (:body response))))
      (let [query ["=" "file" "/foo/bar"]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "file is not a queryable object for resources" (:body response)))))))

(deftest resource-query-paging
  (testing "should not support paging-related query parameters"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [ {:keys [status body]} (get-response nil {k v})]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k))))))))
