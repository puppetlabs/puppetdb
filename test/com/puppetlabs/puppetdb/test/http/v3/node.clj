(ns com.puppetlabs.puppetdb.test.http.v3.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils.node :only [store-example-nodes]]))

(use-fixtures :each with-test-db with-http-app)

;;;; Test the resource listing handlers.
(def c-t pl-http/json-response-content-type)

(defn get-request
  ([path] (get-request path nil))
  ([path query] (get-request path query nil))
  ([path query params]
    (let [query-map (if query
                      {"query" (if (string? query) query (json/generate-string query))}
                      {})
          param-map (merge query-map (if params params {}))
          request (request :get path param-map)
          headers (:headers request)]
      (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v3/nodes" query))))

(defn paged-results
  [query]
  (reduce
    (fn [coll n]
      (let [request (get-request "/v3/nodes"
                      (json/generate-string query) {:limit 1 :offset (* 1 n)})
            {:keys [status body]} (*app* request)
            result  (json/parse-string body true)]
        (is (>= 1 (count result)))
        (concat coll result)))
    []
    (range 4)))

(deftest test-node-queries
  (let [expected (store-example-nodes)]
    (testing "should support paging through nodes"
      (let [results (paged-results nil)]
        (is (= (count results) (count expected)))
        (is (= (set (vals expected))
              (set (map :name results))))))))


