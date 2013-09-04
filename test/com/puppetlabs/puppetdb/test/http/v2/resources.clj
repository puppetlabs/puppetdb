(ns com.puppetlabs.puppetdb.test.http.v2.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils.resources :only [store-example-resources]]))

(use-fixtures :each with-test-db with-http-app)

;;;; Test the resource listing handlers.
(def c-t pl-http/json-response-content-type)

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (if query
                     (request :get path
                              {"query" (if (string? query) query (json/generate-string query))})
                     (request :get path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v2/resources" query))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok   (:status response)))
  (is (= c-t (get-in response [:headers "Content-Type"])))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil)) (str response)))

(deftest resource-list-handler

  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources)]
    (testing "query without filter should not fail"
      (let [response (get-response)
            body     (get response :body "null")]
        (is (= 200 (:status response)))))

    (testing "query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1 bar1}]
                              [["=" "tag" "one"] #{foo1 bar1}]
                              [["=" "tag" "two"] #{foo1 bar1}]
                              [["and"
                                ["=" "certname" "one.local"]
                                ["=" "type" "File"]]
                               #{foo1}]
                              [["=" ["parameter" "ensure"] "file"] #{foo1 bar1}]
                              [["=" ["parameter" "owner"] "root"] #{foo1 bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is-response-equal (get-response query) result)))

    (testing "query exceeding resource-query-limit"
      (with-http-app {:resource-query-limit 1}
        (fn []
          (let [response (get-response ["=" "type" "File"])
                body     (get response :body "null")]
            (is (= (:status response) pl-http/status-internal-error))
            (is (re-find #"more than the maximum number of results" body))))))

    (testing "fact subqueries are supported"
      (let [{:keys [body status]} (get-response ["and"
                                                 ["=" "type" "File"]
                                                 ["in" "certname" ["extract" "certname" ["select-facts"
                                                                                                ["and"
                                                                                                 ["=" "name" "operatingsystem"]
                                                                                                 ["=" "value" "Debian"]]]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{foo1})))

      ;; Using the value of a fact as the title of a resource
      (let [{:keys [body status]} (get-response ["in" "title" ["extract" "value" ["select-facts"
                                                                                         ["=" "name" "message"]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{foo2 bar2}))))

  (testing "resource subqueries are supported"
    ;; Fetch exported resources and their corresponding collected versions
    (let [{:keys [body status]} (get-response ["or"
                                               ["=" "exported" true]
                                               ["and"
                                                ["=" "exported" false]
                                                ["in" "title" ["extract" "title" ["select-resources"
                                                                                                      ["=" "exported" true]]]]]])]
      (is (= status pl-http/status-ok))
      (is (= (set (json/parse-string body true)) #{foo2 bar2}))))

  (testing "error handling"
    (let [response (get-response ["="])
          body     (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"= requires exactly two arguments" body))))

  (testing "query with filter should exclude deactivated nodes"
    ;; After deactivating one.local, it's resources should not appear
    ;; in the results
    (scf-store/deactivate-node! "one.local")

    (doseq [[query result] [[["=" "type" "File"] #{bar1}]
                            [["=" "tag" "one"] #{bar1}]
                            [["=" "tag" "two"] #{bar1}]
                            [["and"
                              ["=" "certname" "one.local"]
                              ["=" "type" "File"]]
                             #{}]
                            [["=" ["parameter" "ensure"] "file"] #{bar1}]
                            [["=" ["parameter" "owner"] "root"] #{bar1}]
                            [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{bar1}]]]
      (is-response-equal (get-response query) result))))

  )
