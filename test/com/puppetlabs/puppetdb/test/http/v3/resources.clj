(ns com.puppetlabs.puppetdb.test.http.v3.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.resources :only [store-example-resources]]))

(use-fixtures :each with-test-db with-http-app)

;;;; Test the resource listing handlers.
;(def c-t pl-http/json-response-content-type)

;(defn get-request
;  ([path] (get-request path nil))
;  ([path query] (get-request path query nil))
;  ([path query params]
;    (let [query-map (if query
;                      {"query" (if (string? query) query (json/generate-string query))}
;                      {})
;          param-map (merge query-map (if params params {}))
;          request (request :get path param-map)
;          headers (:headers request)]
;      (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v3/resources" query))))
;
;(defn paged-results
;  [query]
;  (reduce
;    (fn [coll n]
;      (let [request (get-request "/v3/resources"
;                      (json/generate-string query) {:limit 2 :offset (* 2 n)})
;            {:keys [status body]} (*app* request)
;            result  (json/parse-string body true)]
;        (is (>= 2 (count result)))
;        (concat coll result)))
;    []
;    (range 2)))

(deftest test-resource-queries
  (let [expected (store-example-resources)]
    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through nodes " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    "/v3/resources"
                         :limit   2
                         :total   (count expected)
                         :count?  count?})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                (set results))))))))


