(ns com.puppetlabs.puppetdb.test.http.v3.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.resources :only [store-example-resources]]))

(def endpoint "/v3/resources")

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(defn get-response
  ([]             (get-response nil))
  ([query]        (get-response query {}))
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

(deftest test-resource-queries
  (let [{:keys [foo1 foo2 bar1 bar2] :as expected} (store-example-resources)]
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

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through nodes " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    endpoint
                         :limit   2
                         :total   (count expected)
                         :include-total  count?})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                (set results))))))

    (testing "query by source file / line"
      (let [query ["=" "file" "/foo/bar"]
            result #{bar2}]
        (is-response-equal (get-response query) result))
      (let [query ["~" "file" "foo"]
            result #{bar2}]
        (is-response-equal (get-response query) result))
      (let [query ["=" "line" 22]
            result #{bar2}]
        (is-response-equal (get-response query) result)))

    (testing "query by old field names sourcefile/sourceline"
      (let [query ["=" "sourceline" 22]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "sourceline is not a queryable object for resources" (:body response))))
      (let [query ["~" "sourcefile" "foo"]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "sourcefile cannot be the target of a regexp match" (:body response))))
      (let [query ["=" "sourcefile" "/foo/bar"]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "sourcefile is not a queryable object for resources" (:body response)))))

    (testing "ordering results with order-by"
      (let [order-by {:order-by (json/generate-string [{"field" "certname" "order" "DESC"}
                                                       {"field" "resource" "order" "DESC"}])}
            response (get-response nil order-by)
            actual   (json/parse-string (get response :body "null") true)
            expected [bar2 bar1 foo2 foo1]]
        (is (= pl-http/status-ok (:status response)))
        (is (= actual expected))))

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
      (is-response-equal (get-response query) result)))))
