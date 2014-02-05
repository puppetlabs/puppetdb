(ns com.puppetlabs.puppetdb.test.http.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [puppetlabs.kitchensink.core :as ks]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.resources :only [store-example-resources]]))

(def v2-endpoint "/v2/resources")
(def v3-endpoint "/v3/resources")
(def v4-endpoint "/v4/resources")

(def endpoints [v2-endpoint v3-endpoint v4-endpoint])

(fixt/defixture super-fixture :each fixt/with-test-db fixt/with-http-app)

(defn get-response
  ([endpoint]              (get-response endpoint nil))
  ([endpoint query]        (get-response endpoint query {}))
  ([endpoint query params]
     (let [resp (fixt/*app* (get-request endpoint query params))]
       (if (string? (:body resp))
         resp
         (update-in resp [:body] slurp)))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok   (:status response)))
  (is (= pl-http/json-response-content-type (tu/content-type response)))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil))))

(defn v3->v2-results
  "Munge example resource output from latest API format to v2 format"
  [example-resources]
  (ks/mapvals #(clojure.set/rename-keys % {:file :sourcefile :line :sourceline})
              example-resources))

(deftest resource-endpoint-tests
  (let [results (store-example-resources)
        versioned-results {v2-endpoint (v3->v2-results results)
                           v3-endpoint results
                           v4-endpoint results}]
    (doseq [endpoint endpoints]
      (testing  (str "resource queries for " endpoint ":")
        (super-fixture
         (fn []
           (store-example-resources)
           (let [{:keys [foo1 bar1 foo2 bar2] :as expected} (get versioned-results endpoint)]
             (testing "query without filter should not fail"
               (let [response (get-response endpoint)
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
                 (is-response-equal (get-response endpoint query) result)))

             (testing "fact subqueries are supported"
               (let [{:keys [body status]} (get-response endpoint
                                                         ["and"
                                                          ["=" "type" "File"]
                                                          ["in" "certname" ["extract" "certname" ["select-facts"
                                                                                                  ["and"
                                                                                                   ["=" "name" "operatingsystem"]
                                                                                                   ["=" "value" "Debian"]]]]]])]
                 (is (= status pl-http/status-ok))
                 (is (= (set (json/parse-string body true)) #{foo1})))

               ;; Using the value of a fact as the title of a resource
               (let [{:keys [body status]} (get-response endpoint
                                                         ["in" "title" ["extract" "value" ["select-facts"
                                                                                           ["=" "name" "message"]]]])]
                 (is (= status pl-http/status-ok))
                 (is (= (set (json/parse-string body true)) #{foo2 bar2}))))

             (testing "resource subqueries are supported"
               ;; Fetch exported resources and their corresponding collected versions
               (let [{:keys [body status]} (get-response endpoint
                                                         ["or"
                                                          ["=" "exported" true]
                                                          ["and"
                                                           ["=" "exported" false]
                                                           ["in" "title" ["extract" "title" ["select-resources"
                                                                                             ["=" "exported" true]]]]]])]
                 (is (= status pl-http/status-ok))
                 (is (= (set (json/parse-string body true)) #{foo2 bar2}))))

             (testing "error handling"
               (let [response (get-response endpoint ["="])
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
                 (is-response-equal (get-response endpoint query) result))))))))))

(deftest query-sourcefile-sourceline
  (let [{v3-bar2 :bar2 :as v3-results} (store-example-resources)
        {v2-bar2 :bar2} (v3->v2-results v3-results)]
    (testing "sourcefile and sourceline is queryable in v2"
      (are [query] (is-response-equal (get-response v2-endpoint query) #{v2-bar2})
           ["=" "sourcefile" "/foo/bar"]
           ["~" "sourcefile" "foo"]
           ["=" "sourceline" 22]))

    (testing "sourcefile and source is not supported in v3"
      (let [query ["=" "sourceline" 22]
            response (get-response v3-endpoint query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "sourceline is not a queryable object for resources" (:body response))))
      (let [query ["~" "sourcefile" "foo"]
            response (get-response v3-endpoint query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "sourcefile cannot be the target of a regexp match" (:body response))))
      (let [query ["=" "sourcefile" "/foo/bar"]
            response (get-response v3-endpoint query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "sourcefile is not a queryable object for resources" (:body response)))))

    (testing "querying by file and line is not supported for v2"
      (let [query ["=" "line" 22]
            response (get-response v2-endpoint query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "line is not a queryable object for resources" (:body response))))
      (let [query ["~" "file" "foo"]
            response (get-response v2-endpoint query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "file cannot be the target of a regexp match" (:body response))))
      (let [query ["=" "file" "/foo/bar"]
            response (get-response v2-endpoint query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "file is not a queryable object for resources" (:body response)))))

    (testing "query by file and line is supported for v3"
      (let [query ["=" "file" "/foo/bar"]
            result #{v3-bar2}]
        (is-response-equal (get-response v3-endpoint query) result))
      (let [query ["~" "file" "foo"]
            result #{v3-bar2}]
        (is-response-equal (get-response v3-endpoint query) result))
      (let [query ["=" "line" 22]
            result #{v3-bar2}]
        (is-response-equal (get-response v3-endpoint query) result)))))

(deftest resource-query-paging
  (testing "v2 does not support paging-related query parameters"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [ {:keys [status body]} (get-response v2-endpoint nil {k v})]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k)))))))

  (testing "v3 supports paging via include-total"
    (let [expected (store-example-resources)]
      (doseq [[label count?] [["without" false]
                              ["with" true]]]
        (testing (str "should support paging through nodes " label " counts")
          (let [results (paged-results
                         {:app-fn  fixt/*app*
                          :path    v3-endpoint
                          :limit   2
                          :total   (count expected)
                          :include-total  count?})]
            (is (= (count results) (count expected)))
            (is (= (set (vals expected))
                   (set results)))))))))

(deftest resource-query-result-ordering
  (let [{:keys [foo1 foo2 bar1 bar2] :as expected} (store-example-resources)]
    (testing "ordering results with order-by"
      (let [order-by {:order-by (json/generate-string [{"field" "certname" "order" "DESC"}
                                                       {"field" "resource" "order" "DESC"}])}
            response (get-response v3-endpoint nil order-by)
            actual   (json/parse-string (get response :body "null") true)]
        (is (= pl-http/status-ok (:status response)))
        (is (= actual [bar2 bar1 foo2 foo1]))))))


